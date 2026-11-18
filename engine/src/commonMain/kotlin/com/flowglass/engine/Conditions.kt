package com.flowglass.engine

import com.flowglass.engine.trigger.NetworkType
import com.flowglass.engine.trigger.TriggerMatcher
import com.flowglass.engine.trigger.TriggerSchedule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A condition result plus the reason shown in the run log when it fails. */
data class ConditionEvaluation(val passed: Boolean, val detail: String = "")

/** Read-only host state a condition may query. Shared by trigger dispatch and task blocks. */
interface ConditionEnvironment {
    fun zoneOffsetMinutes(atEpochMs: Long): Int
    fun batteryPercent(): Int?
    fun isCharging(): Boolean
    fun isScreenOn(): Boolean
    fun isDndOn(): Boolean? = null
    fun networkType(): NetworkType = NetworkType.NONE

    /**
     * Last known process state from the LSPosed event cache. Null means unknown or stale; the
     * condition fails closed rather than assuming the process is running.
     */
    fun appProcessRunning(packageName: String, userId: Int): Boolean? = null

    /**
     * File existence for the variable condition. Hosts should use their highest available layer;
     * [layer] is the layer selected by the condition resolver, or null for trigger dispatch.
     */
    fun fileExists(path: String, layer: ExecutionLayer? = null): Boolean? = null
}

/** Path checks and shell quoting shared by the engine tests and the Android host. */
object ConditionPaths {
    /** Returns null when a path cannot safely be passed to a file API or a root command. */
    fun validate(raw: String): String? {
        if (raw.isEmpty()) return null
        if ('\n' in raw || '\r' in raw || '\u0000' in raw) return null
        return raw
    }

    /** Builds a read-only `test -e` command with the path passed as one quoted shell word. */
    fun rootTestCommand(path: String): String {
        require(validate(path) != null) { "文件路径不能包含换行或 NUL" }
        return "test -e " + quote(path)
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/**
 * Single implementation of the condition components.
 *
 * Trigger dispatch and task blocks both call this, so a condition means exactly the same thing
 * before a run and inside one. `{名字}` is expanded with PLAIN semantics; unknown names stay
 * literal, which makes a typo visible instead of silently comparing against "".
 */
object Conditions {
    fun evaluate(
        condition: RuleComponent,
        variables: Map<String, Any?> = emptyMap(),
        environment: ConditionEnvironment,
        nowEpochMs: Long,
        layer: ExecutionLayer? = null,
    ): Boolean = evaluateDetailed(condition, variables, environment, nowEpochMs, layer).passed

    fun evaluateDetailed(
        condition: RuleComponent,
        variables: Map<String, Any?> = emptyMap(),
        environment: ConditionEnvironment,
        nowEpochMs: Long,
        layer: ExecutionLayer? = null,
    ): ConditionEvaluation = when (condition.type) {
        "condition.time_range" -> {
            val range = condition.params["range"] as? JsonObject ?: return falseResult("时间范围无效")
            val from = range["from"]?.jsonPrimitive?.content ?: return falseResult("时间范围缺少开始")
            val to = range["to"]?.jsonPrimitive?.content ?: return falseResult("时间范围缺少结束")
            val now = TriggerSchedule.localTime(nowEpochMs, environment.zoneOffsetMinutes(nowEpochMs))
            ConditionEvaluation(if (from <= to) now >= from && now < to else now >= from || now < to)
        }
        "condition.weekday" -> {
            val mode = condition.params["days"]?.stringValue() ?: return falseResult("星期未设置")
            val weekday = TriggerMatcher.isoWeekday(nowEpochMs, environment.zoneOffsetMinutes(nowEpochMs))
            ConditionEvaluation(when (mode) {
                "everyday" -> true
                "workday" -> weekday in 1..5
                "weekend" -> weekday in 6..7
                else -> false
            })
        }
        "condition.battery_range" -> {
            val range = condition.params["range"] as? JsonObject ?: return falseResult("电量范围无效")
            val min = range["min"]?.jsonPrimitive?.intOrNull ?: return falseResult("电量范围缺少下限")
            val max = range["max"]?.jsonPrimitive?.intOrNull ?: return falseResult("电量范围缺少上限")
            val level = environment.batteryPercent() ?: return falseResult("无法读取电量")
            ConditionEvaluation(level in min..max)
        }
        "condition.charging" -> ConditionEvaluation(when (condition.params["state"]?.stringValue()) {
            "charging" -> environment.isCharging()
            "not_charging" -> !environment.isCharging()
            else -> false
        })
        "condition.dnd_state" -> {
            val dnd = environment.isDndOn() ?: return falseResult("无法读取勿扰状态")
            ConditionEvaluation(when (condition.params["state"]?.stringValue()) {
                "on" -> dnd
                "off" -> !dnd
                else -> false
            })
        }
        "condition.network_type" -> ConditionEvaluation(when (condition.params["network"]?.stringValue()) {
            "wifi" -> environment.networkType() == NetworkType.WIFI
            "mobile" -> environment.networkType() == NetworkType.MOBILE
            "none" -> environment.networkType() == NetworkType.NONE
            else -> false
        })
        "condition.screen_state" -> ConditionEvaluation(when (condition.params["state"]?.stringValue()) {
            "on" -> environment.isScreenOn()
            "off" -> !environment.isScreenOn()
            else -> false
        })
        "condition.app_process_state" -> {
            val target = parseAppTarget(condition.params["package"]?.stringValue())
                ?: return falseResult("应用标识无效")
            val running = environment.appProcessRunning(target.first, target.second)
                ?: return falseResult("进程状态未知")
            ConditionEvaluation(when (condition.params["state"]?.stringValue()) {
                "running" -> running
                "not_running" -> !running
                else -> false
            })
        }
        "condition.variable" -> variable(condition, variables, environment, layer)
        else -> falseResult("未知条件：${condition.type}")
    }

    private fun variable(
        condition: RuleComponent,
        variables: Map<String, Any?>,
        environment: ConditionEnvironment,
        layer: ExecutionLayer?,
    ): ConditionEvaluation {
        val rawLeft = condition.params["left"]?.stringValue().orEmpty()
        val op = condition.params["op"]?.stringValue() ?: "eq"
        val rawRight = condition.params["right"]?.stringValue().orEmpty()
        val left = ParamTemplate.expand(rawLeft, variables)
        val right = ParamTemplate.expand(rawRight, variables)

        if (op in setOf("unset", "set", "empty", "not_empty")) {
            val name = referencedName(rawLeft) ?: return falseResult("请选择变量")
            val exists = variables.containsKey(name) && variables[name] != null
            val content = VariableValues.text(variables[name])
            val passed = when (op) {
                "unset" -> !exists
                "set" -> exists
                "empty" -> exists && content.isEmpty()
                else -> exists && content.isNotEmpty()
            }
            return ConditionEvaluation(passed)
        }

        if (op == "file_exists" || op == "file_not_exists") {
            if (left.isEmpty()) return falseResult("请选择变量或填写路径")
            val path = ConditionPaths.validate(left)
                ?: return falseResult("文件路径不能包含换行或 NUL")
            val exists = environment.fileExists(path, layer)
                ?: return falseResult("文件判断不可用")
            return ConditionEvaluation(if (op == "file_exists") exists else !exists)
        }

        if (op in setOf("gt", "ge", "lt", "le")) {
            val l = left.trim().toLongOrNull() ?: return falseResult("『$left』不是整数")
            val r = right.trim().toLongOrNull() ?: return falseResult("『$right』不是整数")
            val passed = when (op) {
                "gt" -> l > r
                "ge" -> l >= r
                "lt" -> l < r
                else -> l <= r
            }
            return ConditionEvaluation(passed)
        }

        if (op == "contains" || op == "not_contains") {
            val contains = left.contains(right)
            return ConditionEvaluation(if (op == "contains") contains else !contains)
        }

        if (op == "eq" || op == "ne") {
            val equal = equalsByType(left, right)
            return ConditionEvaluation(if (op == "eq") equal else !equal)
        }

        return falseResult("未知判断方式：$op")
    }

    private fun equalsByType(left: String, right: String): Boolean {
        val lb = runCatching { VariableValues.parseBoolean(left) }.getOrNull()
        val rb = runCatching { VariableValues.parseBoolean(right) }.getOrNull()
        if (lb != null && rb != null) return lb == rb
        val li = left.trim().toLongOrNull()
        val ri = right.trim().toLongOrNull()
        if (li != null && ri != null) return li == ri
        return left == right
    }

    private fun referencedName(raw: String): String? {
        val text = raw.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val name = text.substring(1, text.length - 1)
        if (name.isEmpty() || '{' in name || '}' in name) return null
        return name
    }

    private fun falseResult(detail: String): ConditionEvaluation = ConditionEvaluation(false, detail)

    private fun parseAppTarget(raw: String?): Pair<String, Int>? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val at = value.lastIndexOf('@')
        val packageName = if (at < 0) value else value.substring(0, at)
        val userId = if (at < 0) 0 else value.substring(at + 1).toIntOrNull() ?: return null
        if (!PACKAGE_PATTERN.matches(packageName)) return null
        if (userId !in 0..999_999) return null
        return packageName to userId
    }
}

private fun kotlinx.serialization.json.JsonElement.stringValue(): String? = when (this) {
    is JsonObject, is kotlinx.serialization.json.JsonArray -> null
    else -> jsonPrimitive.content
}

private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
