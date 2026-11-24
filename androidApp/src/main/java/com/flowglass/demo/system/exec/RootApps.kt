package com.flowglass.demo.system.exec

import android.content.Context
import android.graphics.drawable.Drawable
import com.flowglass.demo.system.Root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root-backed fallback for profiles that LauncherApps cannot see. Commands are fixed strings or
 * validated package/user arguments; user input is never interpolated into a shell command.
 */
internal object RootApps {
    /**
     * Root scans are read-only and routinely exceed the 500-character budget used for shell
     * actions. This larger budget is still bounded, and a truncated result is rejected instead
     * of being shown as a smaller app list.
     */
    const val SCAN_OUTPUT_LIMIT = 256 * 1024

    private const val USER_MARKER = "__FLOWGLASS_USER__:"
    private const val QUERY_FAILED_MARKER = "__FLOWGLASS_QUERY_FAILED__:"
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    private val userPattern = Regex("\\d{1,6}")

    data class User(val id: Int, val name: String)
    data class Entry(val userId: Int, val packageName: String)

    /**
     * One root invocation for the whole scan: enumerate users, then ask the package service
     * for launcher activities per user. A per-user [Root.runProcess] call would let a strict
     * root manager show a prompt for every profile.
     */
    internal fun scanCommand(): String = """
        users=${'$'}(pm list users)
        printf '%s\n' "${'$'}users"
        printf '%s\n' "${'$'}users" | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p' | while IFS= read -r uid; do
            printf '$USER_MARKER%s\n' "${'$'}uid"
            cmd package query-activities --user "${'$'}uid" --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER || printf '$QUERY_FAILED_MARKER%s\n' "${'$'}uid"
        done
    """.trimIndent()

    /**
     * [requestPermission] is false for the automatic path. It reuses a remembered grant and
     * never starts the manager prompt; only an explicit user action may pass true.
     */
    suspend fun scan(
        context: Context,
        requestPermission: Boolean = false,
    ): Result<List<LaunchableApp>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = authorized(requestPermission)
            val result = RootSessions.run(
                root,
                scanCommand(),
                timeoutMs = 45_000,
                outputLimit = SCAN_OUTPUT_LIMIT,
            )
            check(!result.timedOut) { "Root 扫描超时" }
            check(result.exitCode == 0) { "Root 扫描失败：${result.combinedOutput.take(200)}" }
            check(!result.truncated) {
                "Root 扫描输出超过上限，结果不完整；请稍后重试"
            }

            val users = parseUsers(result.output)
            check(users.isNotEmpty()) { "Root 未返回任何用户资料" }
            val entries = parseScanOutput(result.output)
            check(entries.isNotEmpty()) {
                if (result.output.contains(QUERY_FAILED_MARKER)) {
                    "Root 无法枚举可启动应用；当前系统可能不支持应用查询"
                } else {
                    "Root 未返回任何可启动应用"
                }
            }

            val pm = context.applicationContext.packageManager
            entries.map { entry ->
                val application = runCatching {
                    pm.getApplicationInfo(entry.packageName, 0)
                }.getOrNull()
                val label = application?.let {
                    runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()
                }.orEmpty().ifBlank { entry.packageName }
                val icon: Drawable = application?.let {
                    runCatching { pm.getApplicationIcon(it) }.getOrNull()
                } ?: pm.defaultActivityIcon
                val userName = users.firstOrNull { it.id == entry.userId }?.name
                    ?: "用户 ${entry.userId}"
                LaunchableApp(
                    packageName = entry.packageName,
                    label = label,
                    icon = icon,
                    userId = entry.userId,
                    profileLabel = "Root · $userName",
                )
            }.distinctBy { it.id }
        }
    }

    suspend fun launch(
        context: Context,
        packageName: String,
        userId: Int,
        rootInfo: com.flowglass.demo.system.RootInfo? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(packagePattern.matches(packageName)) { "包名格式不正确" }
            require(userPattern.matches(userId.toString())) { "用户 id 格式不正确" }
            val root = rootInfo ?: authorized(requestPermission = true)
            val component = resolveComponent(
                shell(root, "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER --user $userId $packageName"),
            ) ?: error("Root 未能解析 $packageName 的启动 Activity")
            val result = RootSessions.run(root, "am start --user $userId -n $component")
            check(!result.timedOut) { "Root 启动超时" }
            check(!result.truncated) { "Root 启动输出超过上限" }
            check(result.exitCode == 0) { "Root 启动失败：${result.combinedOutput.take(200)}" }
        }
    }

    private suspend fun authorized(requestPermission: Boolean): com.flowglass.demo.system.RootInfo {
        val info = Root.check(request = requestPermission)
        check(info.granted) {
            if (requestPermission) "缺少 Root 授权，无法扫描分身应用"
            else "Root 尚未授权；自动扫描不会请求授权"
        }
        return info
    }

    private suspend fun shell(info: com.flowglass.demo.system.RootInfo, command: String): String {
        val result = RootSessions.run(info, command, 30_000)
        check(!result.timedOut) { "Root 命令超时" }
        check(!result.truncated) { "Root 命令输出超过上限" }
        check(result.exitCode == 0) { "Root 命令失败：${result.combinedOutput.take(200)}" }
        return result.output
    }

    internal fun parseUsers(output: String): List<User> =
        Regex("UserInfo\\{(\\d+):([^:}]+)").findAll(output).mapNotNull { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            User(id, match.groupValues[2].trim().ifBlank { "用户 $id" })
        }.distinctBy { it.id }.toList()

    /**
     * Parses marker-delimited `cmd package query-activities --brief` output. Only component
     * lines are accepted; an all-package list is deliberately not a fallback because it would
     * make a non-launchable package look like an available app.
     */
    internal fun parseScanOutput(output: String): List<Entry> {
        var currentUser: Int? = null
        val seen = mutableSetOf<Pair<Int, String>>()
        val entries = mutableListOf<Entry>()
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val activeUser = currentUser
            when {
                line.startsWith(USER_MARKER) -> {
                    currentUser = line.removePrefix(USER_MARKER).toIntOrNull()
                        ?.takeIf { userPattern.matches(it.toString()) }
                }
                line.startsWith(QUERY_FAILED_MARKER) -> Unit
                activeUser != null -> {
                    val packageName = parseComponentPackage(line) ?: return@forEach
                    if (seen.add(activeUser to packageName)) {
                        entries += Entry(activeUser, packageName)
                    }
                }
            }
        }
        return entries
    }

    private fun parseComponentPackage(line: String): String? {
        if (!line.contains('/') && !line.contains("component=") && !line.contains("packageName=")) {
            return null
        }
        val candidate = line
            .substringAfter("component=", line)
            .substringBefore(' ')
            .substringBefore('\t')
            .trim()
        val packageName = candidate.substringBefore('/')
        if (packagePattern.matches(packageName)) return packageName

        val dumpPackage = line.substringAfter("packageName=", "")
            .substringBefore(' ')
            .substringBefore('\t')
            .trim()
        return dumpPackage.takeIf { packagePattern.matches(it) }
    }

    internal fun resolveComponent(output: String): String? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') }
            ?.let { line -> line.substringAfter("component=", line).substringAfterLast(' ').trim() }
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) }
}
