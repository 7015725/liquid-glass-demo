package com.flowglass.demo.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class RootInfo(
    val suFound: Boolean,
    val granted: Boolean,
    val uidLine: String,
    val manager: String,
    val suPath: String,
)

object Root {
    private val access = RootAccess(::findSu) { arguments, timeoutMs ->
        if (arguments.size == 3 && arguments[1] == "-c") {
            com.flowglass.demo.system.exec.BoundedProcess.runRoot(arguments[0], arguments[2], timeoutMs)
        } else {
            com.flowglass.demo.system.exec.BoundedProcess.run(arguments, timeoutMs)
        }
    }
    internal suspend fun runProcess(arguments: List<String>, timeoutMs: Long = 30_000) = access.runProcess(arguments, timeoutMs)
    suspend fun check(request: Boolean): RootInfo = access.check(request)
    private val paths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/system_ext/bin/su", "/vendor/bin/su",
    )

    fun findSu(): String? = paths.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
}

/** Execution seam keeps authorization policy independently testable without su. */
internal class RootAccess(
    private val locateSu: () -> String?,
    private val execute: suspend (List<String>, Long) -> com.flowglass.demo.system.exec.ProcessResult,
) {
    private val mutex = Mutex()
    private var granted: RootInfo? = null

    suspend fun runProcess(arguments: List<String>, timeoutMs: Long = 30_000): com.flowglass.demo.system.exec.ProcessResult {
        val authorization = mutex.withLock { granted }
        try {
            return execute(arguments, timeoutMs)
        } catch (denied: com.flowglass.demo.system.exec.RootPermissionDeniedException) {
            mutex.withLock {
                // An older in-flight failure must not evict a newer grant.
                if (granted === authorization && granted?.suPath == arguments.firstOrNull()) granted = null
            }
            throw denied // Never retry a potentially side-effecting command.
        }
    }

    private suspend fun run(vararg cmd: String, timeoutSec: Long = 12): String? = try {
        val result = execute(cmd.toList(), timeoutSec * 1000)
        if (!result.timedOut && result.exitCode == 0) result.output.trim() else null
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** Without [request] only probes; with it runs `su -c id`, which shows the root manager's grant prompt. */
    suspend fun check(request: Boolean): RootInfo = withContext(Dispatchers.IO) { mutex.withLock {
        granted?.let { return@withLock it }
        val path = locateSu()
        val binary = path ?: "su"
        val version = run(binary, "-v", timeoutSec = 4)
        val id = if (request && (path != null || version != null)) run(binary, "-c", "id") else null
        val manager = when {
            version == null -> if (path != null) "未知" else "—"
            version.contains("MAGISK", true) -> "Magisk ${version.substringBefore(':')}"
            version.contains("KernelSU", true) -> "KernelSU ${version.substringBefore(':')}"
            version.contains("APatch", true) -> "APatch ${version.substringBefore(':')}"
            else -> version.take(24)
        }
        RootInfo(
            suFound = path != null || version != null,
            granted = id?.let { Regex("""(?:^|\s)uid=0(?:\(|\s|$)""").containsMatchIn(it) } == true,
            uidLine = id?.substringBefore(' ') ?: "—",
            manager = manager,
            suPath = path ?: if (version != null) "su" else "未找到",
        ).also { if (it.granted) granted = it }
    } }
}
