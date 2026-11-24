package com.flowglass.demo.system.exec

import android.content.Context
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.engine.ScriptVariableAccess

/**
 * Tasker-style Java Code action: parse, gate, run, write back. The runner boundary keeps the
 * BeanShell/process choice out of the engine and the UI.
 */
internal object JavaCodeTask {
    private const val MAX_CODE_CHARS = 64 * 1024

    fun run(
        context: Context,
        cmd: Cmd,
        task: CommandItem,
        variables: ScriptVariableAccess?,
    ): TaskOutcome {
        val code = JavaCodeParameters.code(task.parameter).trim()
        require(code.isNotEmpty()) { "参数格式不正确：代码不能为空" }
        require(code.length <= MAX_CODE_CHARS) { "代码过长（最多 ${MAX_CODE_CHARS / 1024} KiB）" }
        if (JavaCodeGate.isPending(context, code)) {
            return TaskOutcome(
                StepStatus.FAILURE,
                "Java 代码步骤待确认：这是导入的代码，请打开该步骤点「我了解风险，启用」后再运行",
            )
        }
        val timeoutSeconds = JavaCodeParameters.timeoutSeconds(task.parameter)
        val snapshot = variables
            ?.let { access -> access.names().associateWith { name -> access.read(name) } }
            .orEmpty()
        val result = InProcessJavaCodeRunner(context).run(
            JavaCodeRequest(code, timeoutSeconds, cmd.title, cmd.id, snapshot),
        )
        result.writes.forEach { (name, value) -> variables?.write(name, value) }
        return TaskOutcome(result.status, result.detail, result.outputs)
    }
}
