package com.flowglass.demo.system.exec

import android.content.Context
import android.graphics.drawable.Drawable
import com.flowglass.demo.system.Root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root-backed fallback for profiles that LauncherApps cannot see. Commands are fixed argv or
 * validated package/user arguments; user input is never interpolated into a shell command.
 */
internal object RootApps {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    private val userPattern = Regex("\\d{1,6}")

    data class User(val id: Int, val name: String)

    suspend fun scan(context: Context): Result<List<LaunchableApp>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = authorized()
            val users = parseUsers(shell(root, "pm list users"))
            if (users.isEmpty()) error("Root 未返回任何用户资料")
            val pm = context.applicationContext.packageManager
            users.flatMap { user ->
                val packages = parsePackages(shell(root, "pm list packages --user ${user.id}"))
                packages.map { packageName ->
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                    }.getOrDefault(packageName)
                    val icon: Drawable = runCatching {
                        pm.getApplicationIcon(packageName)
                    }.getOrDefault(pm.defaultActivityIcon)
                    LaunchableApp(packageName, label, icon, user.id, "Root · ${user.name}")
                }
            }.distinctBy { it.id }
        }
    }

    suspend fun launch(context: Context, packageName: String, userId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(packagePattern.matches(packageName)) { "包名格式不正确" }
            require(userPattern.matches(userId.toString())) { "用户 id 格式不正确" }
            val root = authorized()
            val component = resolveComponent(shell(root, "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER --user $userId $packageName"))
                ?: error("Root 未能解析 $packageName 的启动 Activity")
            val result = Root.runProcess(listOf(root.suPath, "-c", "am start --user $userId -n $component"))
            check(!result.timedOut) { "Root 启动超时" }
            check(result.exitCode == 0) { "Root 启动失败：${result.output.take(200)}" }
        }
    }

    private suspend fun authorized(): com.flowglass.demo.system.RootInfo {
        val info = Root.check(request = true)
        check(info.granted) { "缺少 Root 授权，无法扫描分身应用" }
        return info
    }

    private suspend fun shell(info: com.flowglass.demo.system.RootInfo, command: String): String {
        val result = Root.runProcess(listOf(info.suPath, "-c", command), 30_000)
        check(!result.timedOut) { "Root 命令超时" }
        check(result.exitCode == 0) { "Root 命令失败：${result.output.take(200)}" }
        return result.output
    }

    internal fun parseUsers(output: String): List<User> =
        Regex("UserInfo\\{(\\d+):([^:}]+)").findAll(output).mapNotNull { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            User(id, match.groupValues[2].trim().ifBlank { "用户 $id" })
        }.distinctBy { it.id }.toList()

    internal fun parsePackages(output: String): List<String> =
        output.lineSequence().mapNotNull { line ->
            line.trim().removePrefix("package:").takeIf { packagePattern.matches(it) }
        }.distinct().sorted().toList()

    internal fun resolveComponent(output: String): String? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') }
            ?.let { line -> line.substringAfter("component=", line).substringAfterLast(' ').trim() }
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) }
}
