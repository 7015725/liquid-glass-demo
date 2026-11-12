package com.flowglass.demo.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RootInfo(
    val suFound: Boolean,
    val granted: Boolean,
    val uidLine: String,
    val manager: String,
    val suPath: String,
)

object Root {
    internal suspend fun runProcess(arguments: List<String>, timeoutMs: Long = 30_000): com.flowglass.demo.system.exec.ProcessResult =
        com.flowglass.demo.system.exec.BoundedProcess.run(arguments, timeoutMs)
    private val paths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/system_ext/bin/su", "/vendor/bin/su",
    )

    fun findSu(): String? = paths.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }

    private suspend fun run(vararg cmd: String, timeoutSec: Long = 12): String? = try {
        val result = runProcess(cmd.toList(), timeoutSec * 1000)
        if (!result.timedOut && result.exitCode == 0) result.output.trim() else null
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** Without [request] only probes; with it runs `su -c id`, which shows the root manager's grant prompt. */
    suspend fun check(request: Boolean): RootInfo = withContext(Dispatchers.IO) {
        val path = findSu()
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
            granted = id?.contains("uid=0") == true,
            uidLine = id?.substringBefore(' ') ?: "—",
            manager = manager,
            suPath = path ?: if (version != null) "su" else "未找到",
        )
    }
}
