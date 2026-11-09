package com.flowglass.demo.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class RootInfo(
    val suFound: Boolean,
    val granted: Boolean,
    val uidLine: String,
    val manager: String,
    val suPath: String,
)

object Root {
    private val paths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/system_ext/bin/su", "/vendor/bin/su",
    )

    fun findSu(): String? = paths.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }

    private fun run(vararg cmd: String, timeoutSec: Long = 12): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy(); null
        } else if (p.exitValue() == 0) {
            p.inputStream.bufferedReader().readText().trim()
        } else null
    } catch (_: Throwable) {
        null
    }

    /** Without [request] only probes; with it runs `su -c id`, which shows the root manager's grant prompt. */
    suspend fun check(request: Boolean): RootInfo = withContext(Dispatchers.IO) {
        val path = findSu()
        val version = if (path != null) run("su", "-v", timeoutSec = 4) else null
        val id = if (request) run("su", "-c", "id") else null
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
            suPath = path ?: "未找到",
        )
    }
}
