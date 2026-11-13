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
import com.flowglass.engine.ComponentRegistry
import com.flowglass.engine.RuleExecutor
import com.flowglass.engine.StepStatus as EngineStepStatus
import com.flowglass.engine.Clock

enum class StepStatus { SUCCESS, FAILURE, SKIPPED }
data class StepResult(val itemId: Long, val name: String, val status: StepStatus, val detail: String, val durationMs: Long)
data class TaskOutcome(val status: StepStatus, val detail: String)
data class RunResult(val cmdId: Long, val reason: String, val steps: List<StepResult>, val durationMs: Long, val ignored: Boolean = false) {
    val ok: Boolean get() = !ignored && steps.none { it.status == StepStatus.FAILURE }
    val detail: String get() = steps.firstOrNull { it.status == StepStatus.FAILURE }?.detail
        ?: steps.joinToString("；") { "${it.name}：${it.detail}" }.ifEmpty { "没有可执行任务" }
}
class ExecutionEngine(context: Context, private val executor: (suspend (Cmd, CommandItem) -> TaskOutcome)? = null) {
    private val context = context.applicationContext
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    suspend fun run(cmd: Cmd, reason: String): RunResult {
        currentCoroutineContext().ensureActive()
        if (!active.add(cmd.id)) return RunResult(cmd.id, reason, emptyList(), 0, ignored = true)
        try {
            if (executor == null) return runWithRuleExecutor(cmd, reason)
            val started = System.nanoTime()
            val steps = mutableListOf<StepResult>()
            var stopped = false
            for (task in cmd.actions.toList()) {
                if (stopped) {
                    steps += StepResult(task.id, task.name, StepStatus.SKIPPED, "前序任务失败，未执行", 0)
                    continue
                }
                val stepStarted = System.nanoTime()
                val outcome = try {
                    executor?.invoke(cmd, task) ?: NativeTasks.run(context, cmd, task)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    TaskOutcome(StepStatus.FAILURE, error.message?.take(500) ?: "任务执行失败")
                }
                currentCoroutineContext().ensureActive()
                steps += StepResult(task.id, task.name, outcome.status, outcome.detail,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stepStarted))
                stopped = outcome.status == StepStatus.FAILURE
            }
            return RunResult(cmd.id, reason, steps,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        } finally {
            active.remove(cmd.id)
        }
    }

    private suspend fun runWithRuleExecutor(cmd: Cmd, reason: String): RunResult {
        val state = PersistedAppState(
            autoCmds = if (cmd.auto) listOf(cmd) else emptyList(),
            oneTaps = if (cmd.auto) emptyList() else listOf(cmd),
            logs = emptyList(),
            running = true,
            mode = 1,
        )
        val document = state.toRuleDocument()
        val rule = document.rules.single()
        val registry = BuiltInComponents.registry()
        val handlers = BuiltInActions.metadata.associate { meta ->
            meta.typeId to Action { _, component ->
                val index = rule.actions.indexOfFirst { it === component }
                val source = cmd.actions.getOrNull(index)
                val parameter = when (component.type) {
                    "action.post_notification" -> {
                        val title = (component.params["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                        val content = (component.params["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                        "$title|$content"
                    }
                    else -> component.params.values.firstOrNull()?.let { json ->
                        (json as? kotlinx.serialization.json.JsonPrimitive)?.content ?: json.toString()
                    }.orEmpty()
                }
                val item = source ?: CommandItem(
                    id = System.nanoTime(),
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
                )
            }
        }
        val startedNanos = System.nanoTime()
        val log = RuleExecutor(registry, handlers, Clock { System.currentTimeMillis() }).run(rule, reason)
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
