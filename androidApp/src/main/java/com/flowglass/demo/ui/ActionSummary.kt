package com.flowglass.demo.ui

import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ParameterCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Plain-language text for editor rows. Summaries read the real parameter (loop count, target
 * rule, variable operation) so editing a value is visible without opening the parameter page.
 */
internal object ActionSummary {
    private val registry = BuiltInComponents.registry()

    /** Formatted parameter values, using the same labels as the parameter cards. */
    fun parameterText(item: CommandItem): String {
        val meta = item.typeId?.let { registry.find(it) } ?: return item.parameter
        val parsed = runCatching { ParameterCodec.parseComponent(meta, item.parameter) }.getOrNull()
            ?: return item.parameter
        return meta.params.mapNotNull { spec ->
            parsed[spec.key]?.let { value -> runCatching { ParameterCodec.format(spec, value) }.getOrNull() }
        }.filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun field(item: CommandItem, key: String): String? {
        val meta = item.typeId?.let { registry.find(it) } ?: return null
        val parsed = runCatching { ParameterCodec.parseComponent(meta, item.parameter) }.getOrNull() ?: return null
        val spec = meta.params.firstOrNull { it.key == key } ?: return null
        val value: JsonElement = parsed[key] ?: return null
        return runCatching { ParameterCodec.format(spec, value) }.getOrNull()
    }

    /** A condition in the form used by trigger/condition cards: name plus readable value. */
    fun conditionText(item: CommandItem): String = when (item.typeId) {
        "condition.charging", "condition.screen_state", "condition.dnd_state" ->
            field(item, "state") ?: item.parameter.ifBlank { item.name }
        "condition.network_type" ->
            field(item, "network") ?: item.parameter.ifBlank { item.name }
        "condition.battery_range" -> "电量 " + (field(item, "range") ?: item.parameter).ifBlank { "未设置" }
        "condition.var_compare" -> listOfNotNull(
            field(item, "left")?.takeIf { it.isNotBlank() },
            field(item, "op"),
            field(item, "right")?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { item.name }
        else -> parameterText(item).ifBlank { item.name }
    }

    /** Name plus readable value, without repeating the name when the value already starts with it. */
    fun conditionRowText(item: CommandItem): String {
        val value = conditionText(item)
        return if (value.startsWith(item.name)) value else "${item.name}：$value"
    }

    fun summarize(item: CommandItem, nested: Int, open: Boolean): String {
        if (!open) return "已折叠 · $nested 项"
        return when (item.typeId) {
            "block.if" -> {
                val condition = item.conditions.firstOrNull()
                    ?: item.branches.firstOrNull()?.conditions?.firstOrNull()
                val head = if (condition == null) "如果（未设置条件）" else "如果 ${conditionText(condition)}"
                if (item.branches.any { it.conditions.isEmpty() }) "$head · 有否则" else head
            }
            "block.loop" -> {
                val mode = item.parameter.substringBefore('|').ifBlank { "按次数" }
                if (mode == "while" || mode == "条件成立时一直循环") "条件成立时重复（最多 1000 次）"
                else {
                    val times = item.parameter.substringAfter('|', "3").toIntOrNull() ?: 3
                    "重复 $times 次 · 内含 ${item.children.size} 步"
                }
            }
            "block.wait_until" -> {
                val condition = item.conditions.firstOrNull()
                val timeout = field(item, "timeout") ?: "30 秒"
                val head = if (condition == null) "等待（未设置条件）" else "等待 ${conditionText(condition)}"
                "$head，最长 $timeout"
            }
            "action.run_rule" -> {
                val target = field(item, "rule")?.ifBlank { null } ?: "未选择指令"
                val wait = field(item, "wait") ?: "等它执行完再继续"
                "执行「$target」· " + if (wait == "等它执行完再继续") "等待完成" else "发起后继续"
            }
            "action.set_var" -> setVarText(item)
            "block.stop" -> {
                val status = field(item, "status") ?: "按成功结束"
                val message = field(item, "message").orEmpty()
                if (message.isBlank()) "停止任务：$status" else "停止任务：$status · $message"
            }
            "block.break" -> "跳出循环"
            "action.toast" -> "提示「" + (field(item, "text") ?: item.parameter) + "」"
            else -> {
                val value = parameterText(item)
                if (value.isBlank() || value == item.name) item.name else "${item.name} $value"
            }
        }
    }

    private fun setVarText(item: CommandItem): String {
        val name = field(item, "name")?.ifBlank { null } ?: "变量"
        val op = field(item, "op") ?: "赋值"
        val value = field(item, "value").orEmpty()
        return when (op) {
            "赋值" -> if (value.isBlank()) "$name 设为空" else "$name 设为 $value"
            "追加文本" -> "$name 追加 $value".trimEnd()
            else -> "$name $op $value".trimEnd()
        }
    }
}
