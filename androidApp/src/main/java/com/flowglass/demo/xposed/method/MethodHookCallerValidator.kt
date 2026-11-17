package com.flowglass.demo.xposed.method

import com.flowglass.engine.methodhook.MethodHookSpec

/** Identity of the process that called the exported Binder, captured by the App service. */
internal data class MethodHookCaller(
    val uid: Int,
    val userId: Int,
    val packages: Set<String>,
)

internal object MethodHookCallerValidator {
    fun matches(caller: MethodHookCaller, spec: MethodHookSpec): Boolean {
        if (caller.uid < 0) return false
        if (caller.userId != spec.userId) return false
        return spec.packageName in caller.packages
    }
}
