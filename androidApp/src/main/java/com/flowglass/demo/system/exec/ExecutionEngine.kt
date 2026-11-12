package com.flowglass.demo.system.exec

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem

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
}
