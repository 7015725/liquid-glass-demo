package com.flowglass.engine.trigger

import com.flowglass.engine.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Read-only state queried at the moment a trigger fires; nothing here is cached by the rule. */
interface TriggerEnvironment {
    fun zoneOffsetMinutes(atEpochMs: Long): Int
    fun batteryPercent(): Int?
    fun isCharging(): Boolean
    fun isScreenOn(): Boolean
}

interface TriggerExecutor {
    fun isRunning(rule: TriggerRule): Boolean
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
    ): ConditionOutcome {
        if (conditions.isEmpty()) return ConditionOutcome(true)
        val failed = conditions.mapNotNull { condition ->
            if (check(condition, environment, nowEpochMs, zoneOffsetMinutes)) null else condition.name
        }
        return ConditionOutcome(failed.isEmpty(), failed)
    }

    private fun check(
        condition: TriggerComponent,
        environment: TriggerEnvironment,
        nowEpochMs: Long,
        zoneOffsetMinutes: Int,
    ): Boolean = when (condition.typeId) {
        "condition.time_range" -> {
            val range = condition.params["range"] as? JsonObject
            val from = range?.get("from")?.jsonPrimitive?.content ?: return false
            val to = range["to"]?.jsonPrimitive?.content ?: return false
            val now = TriggerSchedule.localTime(nowEpochMs, zoneOffsetMinutes)
            if (from <= to) now >= from && now < to else now >= from || now < to
        }
        "condition.weekday" -> {
            val mode = condition.params["days"]?.stringValue() ?: return false
            val weekday = TriggerMatcher.isoWeekday(nowEpochMs, zoneOffsetMinutes)
            when (mode) {
                "everyday" -> true
                "workday" -> weekday in 1..5
                "weekend" -> weekday in 6..7
                else -> false
            }
        }
        "condition.battery_range" -> {
            val range = condition.params["range"] as? JsonObject ?: return false
            val min = range["min"]?.jsonPrimitive?.intOrNull ?: return false
            val max = range["max"]?.jsonPrimitive?.intOrNull ?: return false
            val level = environment.batteryPercent() ?: return false
            level in min..max
        }
        "condition.charging" -> when (condition.params["state"]?.stringValue()) {
            "charging" -> environment.isCharging()
            "not_charging" -> !environment.isCharging()
            else -> false
        }
        "condition.screen_state" -> when (condition.params["state"]?.stringValue()) {
            "on" -> environment.isScreenOn()
            "off" -> !environment.isScreenOn()
            else -> false
        }
        else -> false
    }
}

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

    fun dispatch(event: TriggerEvent, rules: List<TriggerRule>): DispatchOutcome {
        val now = clock.nowEpochMs()
        val offset = environment.zoneOffsetMinutes(now)
        val enabled = rules.filter { it.enabled }
        val allBindings = enabled.flatMap { TriggerPlan.bindings(it) }
        val bindings = allBindings.filter { it.source == event.source }
        val matches = bindings.filter {
            TriggerMatcher.matches(it, event, states, environment.batteryPercent())
        }
        // Drop state of bindings that disappeared (rule removed / trigger edited / disabled).
        val keys = allBindings.map { it.key }.toSet()
        states.removeMissing(keys)
        lastFired.keys.retainAll(keys)
        lastSkipLog.keys.retainAll(enabled.map { it.id }.toSet())

        val executions = mutableListOf<TriggerExecution>()
        val skips = mutableListOf<TriggerSkip>()
        var debounced = 0
        var dropped = 0

        for ((_, matched) in matches.groupBy { it.rule.id }) {
            val binding = matched.firstOrNull { allowsDebounce(it.key, now) }
            if (binding == null) {
                debounced += matched.size
                continue
            }
            lastFired[binding.key] = now
            val rule = binding.rule
            if (executor.isRunning(rule)) {
                dropped++
                continue
            }
            val conditions = TriggerConditions.evaluate(rule.conditions, environment, now, offset)
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
            if (executor.execute(rule, binding, reason, variables)) {
                executions += TriggerExecution(rule, binding, reason, variables)
            } else {
                dropped++
            }
        }
        return DispatchOutcome(executions, skips, debounced, dropped)
    }

    /** Forget debounce/state history when the host re-registers after an explicit stop. */
    fun reset() {
        lastFired.clear()
        lastSkipLog.clear()
        states.clear()
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
