package com.flowglass.demo.system.a11y

/**
 * Enables this app's own accessibility service through root.
 *
 * Android 13+ blocks the Settings toggle for sideloaded apps ("restricted settings"), and an app
 * installed from a chat client is reinstalled as sideloaded on every update, so the block returns
 * each time. The system itself only reads [android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES],
 * so a root write is enough; the UI restriction lives in Settings, not in the framework.
 */
internal object A11ySelfService {
    const val COMPONENT = "com.flowglass.demo/com.flowglass.demo.system.FlowAccessibilityService"

    /** Appends to the existing list and never rewrites entries the user already enabled. */
    fun enableScript(): String = """
        C='$COMPONENT'
        cur=$(settings get secure enabled_accessibility_services)
        [ "${'$'}cur" = "null" ] && cur=""
        case ":${'$'}cur:" in
          *":${'$'}C:"*) echo already ;;
          *) settings put secure enabled_accessibility_services "${'$'}{cur:+${'$'}cur:}${'$'}C"
             settings put secure accessibility_enabled 1
             echo enabled ;;
        esac
    """.trimIndent()

    suspend fun enable(runner: A11yKsuModuleRunner): String {
        val result = runner.run(enableScript())
        if (result.exitCode != 0) return "error"
        return result.output.trim().lines().lastOrNull().orEmpty().ifBlank { "error" }
    }
}
