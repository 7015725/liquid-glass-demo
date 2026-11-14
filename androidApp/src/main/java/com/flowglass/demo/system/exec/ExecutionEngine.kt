package com.flowglass.demo.system.exec

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.PersistedAppState
import com.flowglass.demo.ui.toRuleDocument
import com.flowglass.engine.Action
import com.flowglass.engine.ActionOutcome
import com.flowglass.engine.BuiltInActions
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.CancellationCheckpoint
import com.flowglass.engine.RuleExecutor
import com.flowglass.engine.StepStatus as EngineStepStatus

enum class StepStatus { SUCCESS, FAILURE, SKIPPED }
data class StepResult(val itemId: Long, val name: String, val status: StepStatus, val detail: String, val durationMs: Long)
/** [output] becomes `%{...}` in later steps when the action declares an output key. */
data class TaskOutcome(val status: StepStatus, val detail: String, val output: String? = null)
data class RunResult(val cmdId: Long, val reason: String, val steps: List<StepResult>, val durationMs: Long, val ignored: Boolean = false) {
    val ok: Boolean get() = !ignored && steps.none { it.status == StepStatus.FAILURE }
    val detail: String get() = steps.firstOrNull { it.status == StepStatus.FAILURE }?.detail
        ?: steps.joinToString("；") { "${it.name}：${it.detail}" }.ifEmpty { "没有可执行任务" }
}
class ExecutionEngine(context: Context, private val executor: (suspend (Cmd, CommandItem) -> TaskOutcome)? = null) {
    private val context = context.applicationContext
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    suspend fun run(cmd: Cmd, reason: String, variables: Map<String, Any?> = emptyMap()): RunResult {
        currentCoroutineContext().ensureActive()
        if (!active.add(cmd.id)) return RunResult(cmd.id, reason, emptyList(), 0, ignored = true)
        try {
            return runWithRuleExecutor(cmd, reason, variables)
        } finally {
            active.remove(cmd.id)
        }
    }

    fun isRunning(cmdId: Long): Boolean = cmdId in active

    private suspend fun runWithRuleExecutor(
        cmd: Cmd,
        reason: String,
        variables: Map<String, Any?>,
    ): RunResult {
        val state = PersistedAppState(
            autoCmds = if (cmd.auto) listOf(cmd) else emptyList(),
            oneTaps = if (cmd.auto) emptyList() else listOf(cmd),
            logs = emptyList(),
            running = true,
            mode = 1,
        )
        val document = try {
            state.toRuleDocument(includeTriggersAndConditions = false)
        } catch (invalid: IllegalArgumentException) {
            return RunResult(cmd.id, reason, listOf(StepResult(cmd.id, "参数校验", StepStatus.FAILURE,
                "参数无效：${invalid.message.orEmpty()}", 0)), 0)
        }
        val rule = document.rules.single()
        val registry = BuiltInComponents.registry()
        val handlers = BuiltInActions.metadata.associate { meta ->
            meta.typeId to Action { runContext, component ->
                // The executor reports the step it is running, so parameter expansion (which
                // replaces the component instance) cannot break the item mapping.
                val source = cmd.actions.getOrNull(runContext.currentStep)
                val parameter = com.flowglass.engine.ParameterCodec.formatComponent(meta, component.params)
                val item = source?.copy(name = meta.name, parameter = parameter) ?: CommandItem(
                    id = runContext.currentStep.toLong(),
                    name = meta.name,
                    parameter = parameter,
                    ic = cmd.ic,
                    typeId = component.type,
                )
                val outcome = try {
                    if (executor == null) NativeTasks.run(context, cmd, item)
                    else executor.invoke(cmd, item)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    TaskOutcome(StepStatus.FAILURE, failure.message?.take(500) ?: "任务执行失败")
                }
                ActionOutcome(
                    status = when (outcome.status) {
                        StepStatus.SUCCESS -> EngineStepStatus.SUCCESS
                        StepStatus.FAILURE -> EngineStepStatus.FAILURE
                        StepStatus.SKIPPED -> EngineStepStatus.SKIPPED
                    },
                    detail = outcome.detail,
                    output = outcome.output,
                )
            }
        }
        val startedNanos = System.nanoTime()
        val log = RuleExecutor(
            registry,
            handlers,
            CancellationCheckpoint { currentCoroutineContext().ensureActive() },
        ).run(rule, reason, variables)
        val steps = log.steps.mapIndexed { index, step ->
            val item = cmd.actions.getOrNull(index)
            StepResult(
                itemId = item?.id ?: 0L,
                name = item?.name ?: step.componentType,
                status = when (step.status) {
                    EngineStepStatus.SUCCESS -> StepStatus.SUCCESS
                    EngineStepStatus.FAILURE -> StepStatus.FAILURE
                    EngineStepStatus.SKIPPED -> StepStatus.SKIPPED
                },
                detail = step.detail,
                durationMs = step.durationMs,
            )
        }
        return RunResult(
            cmdId = cmd.id,
            reason = reason,
            steps = steps,
            durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
        )
    }
}
