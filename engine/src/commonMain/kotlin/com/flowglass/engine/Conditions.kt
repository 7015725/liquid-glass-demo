package com.flowglass.engine

import com.flowglass.engine.trigger.NetworkType
import com.flowglass.engine.trigger.TriggerMatcher
import com.flowglass.engine.trigger.TriggerSchedule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Read-only host state a condition may query. Shared by trigger dispatch and task blocks. */
interface ConditionEnvironment {
    fun zoneOffsetMinutes(atEpochMs: Long): Int
    fun batteryPercent(): Int?
    fun isCharging(): Boolean
    fun isScreenOn(): Boolean
    fun isDndOn(): Boolean? = null
    fun networkType(): NetworkType = NetworkType.NONE
}

/**
 * Single implementation of the condition components.
 *
 * Trigger dispatch and task blocks both call this, so a condition means exactly the same thing
 * before a run and inside one. `%{…}` is expanded with PLAIN semantics; unknown names stay
 * literal, which makes a typo visible instead of silently comparing against "".
 */
object Conditions {
    fun evaluate(
        condition: RuleComponent,
        variables: Map<String, Any?> = emptyMap(),
        environment: ConditionEnvironment,
        nowEpochMs: Long,
    ): Boolean = when (condition.type) {
        "condition.time_range" -> {
            val range = condition.params["range"] as? JsonObject ?: return false
            val from = range["from"]?.jsonPrimitive?.content ?: return false
            val to = range["to"]?.jsonPrimitive?.content ?: return false
            val now = TriggerSchedule.localTime(nowEpochMs, environment.zoneOffsetMinutes(nowEpochMs))
            if (from <= to) now >= from && now < to else now >= from || now < to
        }
        "condition.weekday" -> {
            val mode = condition.params["days"]?.stringValue() ?: return false
            val weekday = TriggerMatcher.isoWeekday(nowEpochMs, environment.zoneOffsetMinutes(nowEpochMs))
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
        "condition.dnd_state" -> {
            val dnd = environment.isDndOn() ?: return false
            when (condition.params["state"]?.stringValue()) {
                "on" -> dnd
                "off" -> !dnd
                else -> false
            }
        }
        "condition.network_type" -> when (condition.params["network"]?.stringValue()) {
            "wifi" -> environment.networkType() == NetworkType.WIFI
            "mobile" -> environment.networkType() == NetworkType.MOBILE
            "none" -> environment.networkType() == NetworkType.NONE
            else -> false
        }
        "condition.screen_state" -> when (condition.params["state"]?.stringValue()) {
            "on" -> environment.isScreenOn()
            "off" -> !environment.isScreenOn()
            else -> false
        }
        "condition.var_compare" -> compare(
            left = ParamTemplate.expand(condition.params["left"]?.stringValue().orEmpty(), variables),
            op = condition.params["op"]?.stringValue() ?: "eq",
            right = ParamTemplate.expand(condition.params["right"]?.stringValue().orEmpty(), variables),
        )
        else -> false
    }

    internal fun compare(left: String, op: String, right: String): Boolean {
        val l = left.trim()
        val r = right.trim()
        val ln = l.toDoubleOrNull()
        val rn = r.toDoubleOrNull()
        if (ln != null && rn != null) {
            return when (op) {
                "eq", "=" -> ln == rn
                "ne", "!=" -> ln != rn
                "gt", ">" -> ln > rn
                "ge", ">=" -> ln >= rn
                "lt", "<" -> ln < rn
                "le", "<=" -> ln <= rn
                "contains" -> l.contains(r)
                else -> false
            }
        }
        return when (op) {
            "eq", "=" -> l == r
            "ne", "!=" -> l != r
            "contains" -> l.contains(r)
            else -> false
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.stringValue(): String? = when (this) {
    is JsonObject, is kotlinx.serialization.json.JsonArray -> null
    else -> jsonPrimitive.content
}
