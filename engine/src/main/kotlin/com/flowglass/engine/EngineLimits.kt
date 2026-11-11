package com.flowglass.engine

import kotlinx.serialization.json.*

/** Inclusive authoring limits. A violation is an error, never truncation. */
object EngineLimits {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_RULES = 100
    const val MAX_COMPONENTS = 1000
    const val MAX_DEPTH = 32
    const val MAX_MATCH_NODES = 1000
    const val MAX_VARIABLES = 256
    const val MAX_VALUE_DEPTH = 64
    const val MAX_JSON_DEPTH = 128
    const val MAX_JSON_NODES = 100_000

    internal fun structure(rule: Rule): List<ValidationIssue> {
        if (rule.locals.size > MAX_VARIABLES) return listOf(ValidationIssue("locals", "局部变量数量超过 $MAX_VARIABLES"))
        rule.locals.forEachIndexed { i, v -> valueIssue(v.initial)?.let { return listOf(ValidationIssue("locals[$i].initial", it)) } }
        for ((spec, p) in listOf(rule.triggerMatch to "triggerMatch.tree", rule.conditionMatch to "conditionMatch.tree")) {
            val queue = ArrayDeque<Pair<MatchNode, Int>>()
            spec.tree?.let { queue.add(it to 1) }
            var nodes = 0
            while (queue.isNotEmpty()) {
                val (node, depth) = queue.removeLast()
                if (++nodes > MAX_MATCH_NODES || depth > MAX_DEPTH || nodes + queue.size + node.children.size > MAX_MATCH_NODES)
                    return listOf(ValidationIssue(p, "匹配树节点或深度超过上限"))
                node.children.forEach { queue.add(it to depth + 1) }
            }
        }
        val pending = ArrayDeque<Triple<Component, String, Int>>()
        fun enqueue(list: List<Component>, path: String, depth: Int) {
            list.forEachIndexed { i, c -> pending.add(Triple(c, "$path[$i]", depth)) }
        }
        if (rule.triggers.size + rule.conditions.size + rule.actions.size > MAX_COMPONENTS)
            return listOf(ValidationIssue("components", "组件数量超过 $MAX_COMPONENTS"))
        enqueue(rule.triggers, "triggers", 1); enqueue(rule.conditions, "conditions", 1); enqueue(rule.actions, "actions", 1)
        var count = 0
        while (pending.isNotEmpty()) {
            val (c, path, depth) = pending.removeLast()
            if (++count > MAX_COMPONENTS || count + pending.size + c.children.size + c.elseActions.size > MAX_COMPONENTS)
                return listOf(ValidationIssue(path, "组件总数量超过 $MAX_COMPONENTS"))
            if (depth > MAX_DEPTH) return listOf(ValidationIssue(path, "动作嵌套深度超过 $MAX_DEPTH"))
            c.params.forEach { (key, value) -> valueIssue(value)?.let { return listOf(ValidationIssue("$path.params.$key", it)) } }
            enqueue(c.children, "$path.children", depth + 1); enqueue(c.elseActions, "$path.elseActions", depth + 1)
        }
        return emptyList()
    }

    private val jsonNumber = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
    internal fun valueIssue(value: JsonElement): String? {
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.add(value to 1)
        var count = 0
        var bytes = 0L
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (depth > MAX_VALUE_DEPTH) return "JSON 值嵌套超过 $MAX_VALUE_DEPTH 层"
            if (++count > MAX_JSON_NODES) return "JSON 值节点过多"
            when (node) {
                is JsonObject -> {
                    if (node.size + pending.size + count > MAX_JSON_NODES) return "JSON 值节点过多"
                    node.forEach { (key, child) -> bytes += key.toByteArray(Charsets.UTF_8).size; pending.add(child to depth + 1) }
                }
                is JsonArray -> {
                    if (node.size + pending.size + count > MAX_JSON_NODES) return "JSON 值节点过多"
                    node.forEach { pending.add(it to depth + 1) }
                }
                is JsonPrimitive -> {
                    if (!node.isString && node !== JsonNull && node.booleanOrNull == null && !jsonNumber.matches(node.content)) return "非法 JSON 数值（NaN/Infinity 不可保存）"
                    bytes += node.content.toByteArray(Charsets.UTF_8).size
                }
            }
            if (bytes > MAX_BYTES) return "JSON 值超过1 MiB"
        }
        return null
    }
}
