package com.flowglass.engine.trigger

import com.flowglass.engine.Clock
import com.flowglass.engine.MatchMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Read-only state queried at the moment a trigger fires; nothing here is cached by the rule. */
enum class NetworkType { WIFI, MOBILE, NONE }

interface TriggerEnvironment {
    fun zoneOffsetMinutes(atEpochMs: Long): Int
    fun batteryPercent(): Int?
    fun isCharging(): Boolean
    fun isScreenOn(): Boolean
    fun isDndOn(): Boolean? = null
    fun networkType(): NetworkType = NetworkType.NONE
}

interface TriggerExecutor {
    fun isRunning(rule: TriggerRule): Boolean

    /** Number of active runs for the rule; parallel mode uses this to enforce its cap. */
    fun activeCount(rule: TriggerRule): Int = if (isRunning(rule)) 1 else 0

    /** Cancels active runs for RESTART. Hosts that cannot cancel synchronously may keep the default. */
    fun cancel(rule: TriggerRule) {}

    /** Starts the rule and returns false when the repeat policy dropped this trigger. */
    fun execute(rule: TriggerRule, binding: TriggerBinding, reason: String, variables: Map<String, Any?>): Boolean
}

fun interface TriggerSkipRecorder {
    fun record(rule: TriggerRule, reason: String, detail: String, atEpochMs: Long)
}

data class TriggerExecution(
    val rule: TriggerRule,
    val binding: TriggerBinding,
    val reason: String,
    val variables: Map<String, Any?>,
)

data class TriggerSkip(
    val rule: TriggerRule,
    val reason: String,
    val detail: String,
)

data class DispatchOutcome(
    val executions: List<TriggerExecution> = emptyList(),
    val skips: List<TriggerSkip> = emptyList(),
    val debounced: Int = 0,
    val dropped: Int = 0,
    val queued: Int = 0,
)

data class ConditionOutcome(val passed: Boolean, val failed: List<String> = emptyList()) {
    val detail: String get() = "条件不满足：${failed.joinToString("、")}"
}

object TriggerConditions {
    fun evaluate(
        conditions: List<TriggerComponent>,
        environment: TriggerEnvironment,
        nowEpochMs: Long,
        zoneOffsetMinutes: Int,
        variables: Map<String, Any?> = emptyMap(),
    ): ConditionOutcome {
        if (conditions.isEmpty()) return ConditionOutcome(true)
        val conditionEnvironment = object : com.flowglass.engine.ConditionEnvironment {
            override fun zoneOffsetMinutes(atEpochMs: Long): Int = environment.zoneOffsetMinutes(atEpochMs)
            override fun batteryPercent(): Int? = environment.batteryPercent()
            override fun isCharging(): Boolean = environment.isCharging()
            override fun isScreenOn(): Boolean = environment.isScreenOn()
            override fun isDndOn(): Boolean? = environment.isDndOn()
            override fun networkType(): NetworkType = environment.networkType()
        }
        val failed = conditions.mapNotNull { condition ->
            val passed = com.flowglass.engine.Conditions.evaluate(
                com.flowglass.engine.RuleComponent(condition.typeId, condition.params),
                variables,
                conditionEnvironment,
                nowEpochMs,
            )
            if (passed) null else condition.name
        }
        return ConditionOutcome(failed.isEmpty(), failed)
    }
}

private const val MAX_PARALLEL_RUNS = 3
private const val MAX_QUEUED_RUNS = 10

/**
 * Event -> trigger match -> debounce -> condition -> execute.
 *
 * `1b-1` deliberately implements only the "any trigger" and "all conditions" modes and the
 * "drop" repeat policy; the stored match modes are evaluated in `1c`.
 */
class TriggerDispatcher(
    private val clock: Clock,
    private val environment: TriggerEnvironment,
    private val executor: TriggerExecutor,
    private val skipRecorder: TriggerSkipRecorder,
    private val debounceMs: Long = 2_000,
    private val skipLogIntervalMs: Long = 60_000,
) {
    private val lastFired = mutableMapOf<String, Long>()
    private val lastSkipLog = mutableMapOf<String, Long>()
    private val states = TriggerStateStore()
    private val lastEvents = mutableMapOf<String, Long>()
    private val queues = mutableMapOf<String, ArrayDeque<TriggerExecution>>()

    fun dispatch(event: TriggerEvent, rules: List<TriggerRule>): DispatchOutcome {
        val now = clock.nowEpochMs()
        val offset = environment.zoneOffsetMinutes(now)
        val enabled = rules.filter { it.enabled }
        val allBindings = enabled.flatMap { TriggerPlan.bindings(it) }
        val keys = allBindings.map { it.key }.toSet()
        val debounceKeys = keys + enabled.map { "${it.id}#all" }
        states.removeMissing(keys)
        lastFired.keys.retainAll(debounceKeys)
        lastSkipLog.keys.retainAll(enabled.map { it.id }.toSet())
        lastEvents.keys.retainAll(keys)
        queues.keys.retainAll(enabled.map { it.id }.toSet())

        val bindings = allBindings.filter { it.source == event.source }
        val matched = bindings.filter {
            TriggerMatcher.matches(it, event, states, environment.batteryPercent())
        }
        matched.forEach { lastEvents[it.key] = now }

        val executions = mutableListOf<TriggerExecution>()
        val skips = mutableListOf<TriggerSkip>()
        var debounced = 0
        var dropped = 0
        var queued = 0

        for (rule in enabled) {
            val ruleBindings = allBindings.filter { it.rule.id == rule.id }
            val eventBindings = ruleBindings.filter { it.source == event.source }
            if (eventBindings.isEmpty()) continue // No trigger of this rule listens to this event.
            val binding = if (rule.triggerMatch.mode == MatchMode.ALL) {
                if (!allTriggersSatisfied(ruleBindings, rule.triggerMatch.windowSeconds, now)) continue
                eventBindings.firstOrNull { candidate -> matched.any { it.key == candidate.key } }
                    ?: eventBindings.first()
            } else {
                matched.firstOrNull { it.rule.id == rule.id } ?: continue
            }
            val debounceKey = if (rule.triggerMatch.mode == MatchMode.ALL) "${rule.id}#all" else binding.key
            if (!allowsDebounce(debounceKey, now)) {
                debounced += ruleBindings.size
                continue
            }
            val conditions = TriggerConditions.evaluate(rule.conditions, environment, now, offset, variablesOf(event))
            if (!conditions.passed) {
                val detail = conditions.detail
                val previous = lastSkipLog[rule.id]
                if (previous == null || now - previous >= skipLogIntervalMs) {
                    lastSkipLog[rule.id] = now
                    val skip = TriggerSkip(rule, reasonFor(binding), detail)
                    skips += skip
                    skipRecorder.record(rule, skip.reason, detail, now)
                }
                continue
            }
            val reason = reasonFor(binding)
            val variables = variablesFor(event, now, offset)
            val active = executor.activeCount(rule)
            when (rule.runMode) {
                com.flowglass.engine.RunMode.DROP -> if (active > 0) {
                    dropped++
                    continue
                }
                com.flowglass.engine.RunMode.QUEUE -> if (active > 0) {
                    val queue = queues.getOrPut(rule.id) { ArrayDeque() }
                    if (queue.size >= MAX_QUEUED_RUNS) dropped++ else {
                        queue.addLast(TriggerExecution(rule, binding, reason, variables))
                        queued++
                    }
                    continue
                }
                com.flowglass.engine.RunMode.RESTART -> if (active > 0) executor.cancel(rule)
                com.flowglass.engine.RunMode.PARALLEL -> if (active >= MAX_PARALLEL_RUNS) {
                    dropped++
                    continue
                }
            }
            lastFired[debounceKey] = now
            if (rule.triggerMatch.mode == MatchMode.ALL) {
                // A conjunction is consumed by one execution; require fresh events next time.
                ruleBindings.forEach { lastEvents.remove(it.key) }
            }
            if (executor.execute(rule, binding, reason, variables)) {
                executions += TriggerExecution(rule, binding, reason, variables)
            } else {
                dropped++
            }
        }
        return DispatchOutcome(executions, skips, debounced, dropped, queued)
    }

    /**
     * Called by the host when a run completes. A queued trigger starts immediately if the rule is
     * idle; the queue is bounded so a burst cannot grow without limit.
     */
    fun onRunFinished(rule: TriggerRule) {
        val queue = queues[rule.id] ?: return
        while (queue.isNotEmpty() && executor.activeCount(rule) == 0) {
            val next = queue.removeFirst()
            if (executor.execute(next.rule, next.binding, next.reason, next.variables)) return
        }
        if (queue.isEmpty()) queues.remove(rule.id)
    }

    private fun allTriggersSatisfied(
        bindings: List<TriggerBinding>,
        windowSeconds: Int,
        now: Long,
    ): Boolean = bindings.all { binding ->
        if (TriggerMatcher.isStateful(binding)) {
            TriggerMatcher.matchesState(binding, environment)
        } else {
            val at = lastEvents[binding.key] ?: return@all false
            now - at <= windowSeconds.coerceAtLeast(0) * 1_000L
        }
    }

    /** Forget debounce/state history when the host re-registers after an explicit stop. */
    fun reset() {
        lastFired.clear()
        lastSkipLog.clear()
        states.clear()
        lastEvents.clear()
        queues.clear()
    }

    /** Drops state for rules that are no longer listening, so a re-enable starts from a baseline. */
    fun prune(rules: List<TriggerRule>) {
        val keys = rules.flatMap { TriggerPlan.bindings(it) }.map { it.key }.toSet()
        states.removeMissing(keys)
        lastFired.keys.retainAll(keys + rules.map { "${it.id}#all" })
        lastSkipLog.keys.retainAll(rules.map { it.id }.toSet())
        lastEvents.keys.retainAll(keys)
        val ruleIds = rules.map { it.id }.toSet()
        queues.keys.retainAll(ruleIds)
    }

    private fun allowsDebounce(key: String, now: Long): Boolean {
        val previous = lastFired[key] ?: return true
        return now - previous >= debounceMs
    }

    private fun reasonFor(binding: TriggerBinding): String = "触发：${binding.component.name}"

    private fun variablesFor(event: TriggerEvent, now: Long, offset: Int): Map<String, Any?> {
        val variables = linkedMapOf<String, Any?>(
            "event.time" to TriggerSchedule.localTime(event.atEpochMs, offset),
        )
        event.data["battery"]?.toIntOrNull()?.let { variables["event.battery"] = it }
        for ((key, value) in event.data) {
            if (key == "zone_offset_minutes" || key == "battery") continue
            variables["event.$key"] = value
        }
        return variables
    }
}

/** Event data is visible to conditions as event.time / event.battery / ... */
private fun variablesOf(event: TriggerEvent): Map<String, Any?> =
    event.data.mapKeys { (key, _) -> "event.$key" }
