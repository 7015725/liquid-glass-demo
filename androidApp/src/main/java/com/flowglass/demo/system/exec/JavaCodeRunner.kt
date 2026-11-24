package com.flowglass.demo.system.exec

/** Everything a runner needs; safe to serialize across a process boundary in Phase 2. */
internal data class JavaCodeRequest(
    val code: String,
    val timeoutSeconds: Long,
    val taskName: String,
    val taskId: Long,
    val variables: Map<String, Any?>,
)

internal data class JavaCodeResult(
    val status: StepStatus,
    val detail: String,
    val outputs: Map<String, Any?> = emptyMap(),
    val writes: Map<String, Any?> = emptyMap(),
)

/**
 * Execution seam. In-process now; Phase 2 can add a runner that talks to a `:java_code`
 * service without touching JavaCodeTask, the gate or the UI.
 */
internal interface JavaCodeRunner {
    fun run(request: JavaCodeRequest): JavaCodeResult
}
