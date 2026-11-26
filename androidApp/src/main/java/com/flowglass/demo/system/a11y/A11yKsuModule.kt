package com.flowglass.demo.system.a11y

import com.flowglass.demo.system.RootInfo
import com.flowglass.demo.system.exec.ProcessResult
import com.flowglass.demo.system.exec.RootSessions

/** Runs one shell script as root. Injected so the module logic is testable without a device. */
internal fun interface A11yKsuModuleRunner {
    suspend fun run(script: String): ProcessResult
}

/**
 * KernelSU / Magisk module backend.
 *
 * KernelSU and Magisk both run `<module>/service.sh` at boot as root, so one module folder serves
 * both. Like the hook backend it only re-enables components that are still in the system's enabled
 * list — a component the user removed is never added back.
 */
internal object A11yKsuModule {
    const val DIR = "/data/adb/modules/flowglass_a11y_keepalive"
    const val STATE_DIR = "/data/adb/flowglass"
    const val CONF = "$STATE_DIR/a11y_keepalive.conf"
    private const val SERVICE = "$DIR/service.sh"

    fun configText(targets: Set<String>): String =
        targets.sorted().joinToString(separator = "\n", postfix = "\n")

    fun installScript(serviceSh: String): String = """
        set -e
        mkdir -p '$DIR' '$STATE_DIR'
        cat > '$DIR/module.prop' <<'FLOWGLASS_MODULE'
        id=flowglass_a11y_keepalive
        name=流光无障碍保活
        version=v1
        versionCode=1
        author=Flowglass
        description=在无障碍服务进程消失后按 $CONF 逐项重新启用。仅对用户勾选的服务生效。
        FLOWGLASS_MODULE
        cat > '$SERVICE' <<'FLOWGLASS_SERVICE'
        $serviceSh
        FLOWGLASS_SERVICE
        chmod 0755 '$SERVICE'
    """.trimIndent()

    suspend fun install(
        runner: A11yKsuModuleRunner,
        serviceSh: String,
        targets: Set<String>,
    ): ProcessResult {
        val result = runner.run(installScript(serviceSh))
        if (result.exitCode != 0) return result
        return runner.run("mkdir -p '$STATE_DIR'; printf %s ${shellQuote(configText(targets))} > '$CONF'")
    }

    suspend fun refresh(runner: A11yKsuModuleRunner, targets: Set<String>): ProcessResult =
        runner.run("mkdir -p '$STATE_DIR'; printf %s ${shellQuote(configText(targets))} > '$CONF'")

    suspend fun ensureRunning(runner: A11yKsuModuleRunner): ProcessResult =
        runner.run("( sh '$SERVICE' >/dev/null 2>&1 & )")

    suspend fun uninstall(runner: A11yKsuModuleRunner): ProcessResult =
        runner.run("( sh '$SERVICE' >/dev/null 2>&1 & ) ; rm -rf '$DIR' '$STATE_DIR'")

    suspend fun isInstalled(runner: A11yKsuModuleRunner): Boolean =
        runner.run("[ -f '$SERVICE' ]").exitCode == 0

    suspend fun readLog(runner: A11yKsuModuleRunner, maxBytes: Int = 8_000): String {
        val text = runner.run("tail -c $maxBytes '$STATE_DIR/a11y_keepalive.log' 2>/dev/null").output
        return text.takeLast(maxBytes)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** One shared root shell; install steps are short and serialized by the session itself. */
internal fun productionRunner(root: RootInfo): A11yKsuModuleRunner = A11yKsuModuleRunner { script ->
    RootSessions.run(root, script, timeoutMs = 60_000, outputLimit = 8_000)
}
