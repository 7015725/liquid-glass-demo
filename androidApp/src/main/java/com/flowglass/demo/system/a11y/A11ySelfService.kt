package com.flowglass.demo.system.a11y

/**
 * Enables this app's own accessibility service through root.
 *
 * Android 13+ blocks the Settings toggle for sideloaded apps ("restricted settings"), and an app
 * installed from a chat client is reinstalled as sideloaded on every update, so the block returns
 * each time. The framework only reads ENABLED_ACCESSIBILITY_SERVICES, so a root write is enough.
 *
 * The script never trusts itself: it uses the absolute `settings` path (the privileged shell may
 * not inherit a PATH that resolves it) and re-reads the value afterwards, so a silent failure
 * cannot be reported as success.
 */
internal object A11ySelfService {
    const val COMPONENT = "com.flowglass.demo/com.flowglass.demo.system.FlowAccessibilityService"

    sealed interface Result {
        data object Enabled : Result
        data object Already : Result
        data class Failed(val detail: String) : Result
    }

    fun enableScript(): String = """
        S=/system/bin/settings
        C='$COMPONENT'
        [ -x "${'$'}S" ] || { echo 'no_settings_binary'; exit 3; }
        cur=${'$'}(${'$'}S get secure enabled_accessibility_services 2>&1)
        [ "${'$'}cur" = "null" ] && cur=""
        case ":${'$'}cur:" in
          *":${'$'}C:"*) echo 'already'; exit 0 ;;
        esac
        out=${'$'}(${'$'}S put secure enabled_accessibility_services "${'$'}{cur:+${'$'}cur:}${'$'}C" 2>&1) || { echo "put_failed:${'$'}{out:-no_output}"; exit 4; }
        mout=${'$'}(${'$'}S put secure accessibility_enabled 1 2>&1) || { echo "master_failed:${'$'}{mout:-no_output}"; exit 5; }
        after=${'$'}(${'$'}S get secure enabled_accessibility_services 2>&1)
        case ":${'$'}after:" in
          *":${'$'}C:"*) echo 'enabled' ;;
          *) echo "verify_failed:${'$'}after" ;;
        esac
    """.trimIndent()

    suspend fun enable(runner: A11yKsuModuleRunner): Result {
        val result = runner.run(enableScript())
        val verdict = result.output.trim().lines().lastOrNull().orEmpty()
        if (result.exitCode != 0) {
            val stderr = result.stderr.trim().takeIf { it.isNotEmpty() }?.let { " / $it" }.orEmpty()
            return Result.Failed(verdict.ifBlank { "exit=${result.exitCode}" } + stderr)
        }
        return when {
            verdict == "enabled" -> Result.Enabled
            verdict == "already" -> Result.Already
            else -> Result.Failed(verdict.ifBlank { "empty_output" })
        }
    }
}
