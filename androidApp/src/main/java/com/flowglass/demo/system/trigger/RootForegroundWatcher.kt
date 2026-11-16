package com.flowglass.demo.system.trigger

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.exec.RootSessions
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class ForegroundApp(val packageName: String, val userId: Int)

/**
 * Clone-only fallback for foreground events. The main app is still watched by the normal
 * UsageStats watcher; this poller only reads the system task dump when a rule actually names a
 * clone, and only while the screen is interactive.
 */
internal class RootForegroundWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val emit: (String, TriggerSource, Map<String, String>) -> Unit,
) {
    private var job: Job? = null
    private var lastIdentity: String? = null

    fun start(hasCloneTarget: () -> Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            val root = runCatching { Root.check(request = false) }.getOrNull()
            if (root?.granted != true) return@launch
            while (isActive) {
                if (hasCloneTarget() && isScreenOn()) {
                    val result = runCatching {
                        RootSessions.run(root, "dumpsys activity activities", timeoutMs = 8_000, outputLimit = 128 * 1024)
                    }.getOrNull()
                    val foreground = result?.takeUnless { it.timedOut || it.truncated }?.output?.let(::parseForeground)
                    if (foreground != null && foreground.userId != 0) {
                        val identity = "${foreground.packageName}@${foreground.userId}"
                        if (identity != lastIdentity) {
                            lastIdentity = identity
                            emit(
                                TriggerKinds.APP,
                                TriggerSource.APP,
                                mapOf(
                                    "app" to identity,
                                    "app.user" to foreground.userId.toString(),
                                ),
                            )
                        }
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastIdentity = null
    }

    private fun isScreenOn(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    companion object {
        private const val POLL_MS = 2_000L
        private val TOP_RESUMED = Regex(
            """topResumedActivity=ActivityRecord\{[^\n]*?\bu(\d+)\s+([A-Za-z0-9_.]+)/""",
        )
        private val RESUMED = Regex(
            """mResumedActivity:\s*ActivityRecord\{[^\n]*?\bu(\d+)\s+([A-Za-z0-9_.]+)/""",
        )

        internal fun parseForeground(output: String): ForegroundApp? {
            val match = TOP_RESUMED.find(output) ?: RESUMED.find(output) ?: return null
            val userId = match.groupValues[1].toIntOrNull() ?: return null
            val packageName = match.groupValues[2]
            return ForegroundApp(packageName, userId)
        }
    }
}
