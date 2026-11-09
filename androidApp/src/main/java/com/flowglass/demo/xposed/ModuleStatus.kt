package com.flowglass.demo.xposed

/** Replaced by [XposedEntry] when the module is enabled in LSPosed. Kept by ProGuard rules. */
object ModuleStatus {
    @JvmStatic
    fun isActive(): Boolean = false.also { touch() }

    @JvmStatic
    fun frameworkVersion(): Int = 0.also { touch() }

    // prevents the compiler/R8 from constant-folding the methods above
    @Volatile private var sink = 0
    private fun touch() { sink++ }
}
