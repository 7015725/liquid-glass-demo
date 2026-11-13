package com.flowglass.engine

import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

@Serializable
enum class StepStatus { SUCCESS, FAILURE, SKIPPED }

@Serializable
data class StepResult(
    val componentType: String,
    val status: StepStatus,
    val detail: String,
    val durationMs: Long,
)

@Serializable
data class RunLog(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val reason: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val status: StepStatus,
    val detail: String,
    val steps: List<StepResult>,
)

data class RunContext(
    val rule: Rule,
    val reason: String,
    val variables: MutableMap<String, Any?> = linkedMapOf(),
)

data class ActionOutcome(val status: StepStatus, val detail: String)

interface Trigger {
    val meta: ComponentMeta
    fun start(emit: (Map<String, String>) -> Unit)
    fun stop()
}

fun interface Condition {
    suspend fun check(context: RunContext, component: RuleComponent): Boolean
}

fun interface Action {
    suspend fun run(context: RunContext, component: RuleComponent): ActionOutcome
}

fun interface Clock {
    fun nowEpochMs(): Long
}

/** The host must throw CancellationException when the current execution is cancelled. */
fun interface CancellationCheckpoint {
    suspend fun ensureActive()
}

class RuleExecutor(
    private val registry: ComponentRegistry,
    private val actions: Map<String, Action>,
    private val cancellationCheckpoint: CancellationCheckpoint,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) {
    suspend fun run(rule: Rule, reason: String): RunLog {
        cancellationCheckpoint.ensureActive()
        val start = clock.nowEpochMs()
        val monotonicStart = kotlin.time.TimeSource.Monotonic.markNow()
        val steps = mutableListOf<StepResult>()
        var failed = false
        val context = RunContext(rule, reason)

        for (component in rule.actions) {
            cancellationCheckpoint.ensureActive()
            if (failed) {
                steps += StepResult(component.type, StepStatus.SKIPPED, "前序动作失败，未执行", 0)
                continue
            }
            val meta = registry.find(component.type)
            val action = actions[component.type]
            if (meta == null || meta.kind != ComponentKind.ACTION || action == null) {
                steps += StepResult(component.type, StepStatus.SKIPPED, "暂不支持该动作", 0)
                continue
            }
            val stepStart = kotlin.time.TimeSource.Monotonic.markNow()
            val result = try {
                action.run(context, component)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ActionOutcome(StepStatus.FAILURE, error.message?.take(500) ?: "动作执行失败")
            }
            // A suspend action may complete synchronously after cancelling its caller.
            cancellationCheckpoint.ensureActive()
            val duration = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                stepStart.elapsedNow().inWholeNanoseconds.coerceAtLeast(0),
            )
            steps += StepResult(component.type, result.status, result.detail, duration)
            failed = result.status == StepStatus.FAILURE
        }

        val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            monotonicStart.elapsedNow().inWholeNanoseconds.coerceAtLeast(0),
        )
        val status = when {
            steps.any { it.status == StepStatus.FAILURE } -> StepStatus.FAILURE
            steps.isEmpty() || steps.all { it.status == StepStatus.SKIPPED } -> StepStatus.SKIPPED
            else -> StepStatus.SUCCESS
        }
        return RunLog(
            id = newIdString(),
            ruleId = rule.id,
            ruleName = rule.name,
            reason = reason,
            startedAtEpochMs = start,
            durationMs = elapsed,
            status = status,
            detail = steps.firstOrNull { it.status == StepStatus.FAILURE }?.detail
                ?: steps.joinToString("；") { it.detail }.ifEmpty { "没有可执行动作" },
            steps = steps,
        )
    }
}
