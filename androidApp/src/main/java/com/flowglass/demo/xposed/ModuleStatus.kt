package com.flowglass.demo.xposed

/**
 * Replaced by [XposedEntry] when the classic module is enabled in LSPosed. The modern API101
 * framework does not inject the module into its own app, so [ModernModuleStatus] also marks this
 * object through XposedServiceHelper.
 *
 * `isActive` intentionally accepts either activation path: old frameworks keep the self-hook,
 * while API101 binds the framework service. The system-hook page still has to wait for a fresh
 * heartbeat before it calls the LSPosed layer READY.
 */
object ModuleStatus {
    @Volatile private var modernActive = false
    @Volatile private var modernApi = 0
    @Volatile private var modernFrameworkName = ""
    @Volatile private var modernFrameworkVersion = ""
    @Volatile private var modernFrameworkVersionCode = 0L
    @Volatile private var modernScope: List<String> = emptyList()

    @JvmStatic
    fun isActive(): Boolean = modernActive.also { touch() }

    @JvmStatic
    fun frameworkVersion(): Int = modernApi.also { touch() }

    @JvmStatic
    fun isModern(): Boolean = modernActive

    @JvmStatic
    fun frameworkName(): String = modernFrameworkName

    @JvmStatic
    fun frameworkVersionName(): String = modernFrameworkVersion

    @JvmStatic
    fun frameworkVersionCode(): Long = modernFrameworkVersionCode

    @JvmStatic
    fun scope(): List<String> = modernScope

    @JvmStatic
    fun markModern(
        apiVersion: Int,
        frameworkName: String,
        frameworkVersion: String,
        frameworkVersionCode: Long,
        scope: List<String>,
    ) {
        modernApi = apiVersion
        modernFrameworkName = frameworkName
        modernFrameworkVersion = frameworkVersion
        modernFrameworkVersionCode = frameworkVersionCode
        modernScope = scope
        modernActive = true
    }

    @JvmStatic
    fun clearModern() {
        modernActive = false
        modernApi = 0
        modernFrameworkName = ""
        modernFrameworkVersion = ""
        modernFrameworkVersionCode = 0L
        modernScope = emptyList()
    }

    // prevents the compiler/R8 from constant-folding the methods above
    @Volatile private var sink = 0
    private fun touch() { sink++ }
}
