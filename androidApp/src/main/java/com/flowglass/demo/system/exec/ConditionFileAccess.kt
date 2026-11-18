package com.flowglass.demo.system.exec

import com.flowglass.demo.system.Root
import com.flowglass.engine.ConditionPaths
import com.flowglass.engine.ExecutionLayer
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * File existence for the variable condition. A ready Root layer runs `test -e` in the root
 * shell; otherwise the normal file API is used. The path is validated and single-quoted before
 * it reaches a shell.
 */
internal object ConditionFileAccess {
    fun exists(rawPath: String, layer: ExecutionLayer? = null): Boolean? {
        val path = ConditionPaths.validate(rawPath) ?: return null
        // Always try the highest layer first: a granted Root session can see paths that the
        // app sandbox cannot, even when the resolver has not refreshed its snapshot yet.
        val root = runCatching { runBlocking { Root.check(request = false) } }.getOrNull()
        if (root?.granted == true) {
            val result = runCatching {
                runBlocking {
                    RootSessions.run(
                        root,
                        ConditionPaths.rootTestCommand(path),
                        timeoutMs = 5_000,
                        outputLimit = 0,
                    )
                }
            }.getOrNull()
            if (result != null && !result.timedOut) {
                if (result.exitCode == 0) return true
                if (result.exitCode == 1) return false
            }
        }
        return runCatching { File(path).exists() }.getOrNull()
    }
}
