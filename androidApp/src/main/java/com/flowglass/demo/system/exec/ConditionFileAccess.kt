package com.flowglass.demo.system.exec

import android.os.Looper
import com.flowglass.demo.BuildConfig
import com.flowglass.demo.system.Root
import com.flowglass.engine.ConditionPaths
import com.flowglass.engine.ExecutionLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File existence for the variable condition. A ready Root layer runs `test -e` in the root
 * shell; otherwise the normal file API is used. The path is validated and single-quoted before
 * it reaches a shell.
 *
 * Trigger dispatch uses the suspending [existsAsync] on its serial worker. [exists] remains for
 * task execution, where the caller is already on a background coroutine; in a debug build it
 * fails loudly if that invariant is ever broken on the main thread.
 */
internal object ConditionFileAccess {
    fun exists(rawPath: String, layer: ExecutionLayer? = null): Boolean? {
        requireNotMainThread()
        return runBlocking { existsAsync(rawPath, layer) }
    }

    suspend fun existsAsync(rawPath: String, layer: ExecutionLayer? = null): Boolean? {
        requireNotMainThread()
        val path = ConditionPaths.validate(rawPath) ?: return null
        // Always try the highest layer first: a granted Root session can see paths that the
        // app sandbox cannot, even when the resolver has not refreshed its snapshot yet.
        val root = runCatching { Root.check(request = false) }.getOrNull()
        if (root?.granted == true) {
            val result = runCatching {
                RootSessions.run(
                    root,
                    ConditionPaths.rootTestCommand(path),
                    timeoutMs = 5_000,
                    outputLimit = 0,
                )
            }.getOrNull()
            if (result != null && !result.timedOut) {
                if (result.exitCode == 0) return true
                if (result.exitCode == 1) return false
            }
        }
        return runCatching { withContext(Dispatchers.IO) { File(path).exists() } }.getOrNull()
    }

    private fun requireNotMainThread() {
        if (BuildConfig.DEBUG && Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("ConditionFileAccess must not run on the main thread")
        }
    }
}
