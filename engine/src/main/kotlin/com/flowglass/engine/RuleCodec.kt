package com.flowglass.engine

import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

class RuleValidationException(val issues: List<ValidationIssue>) : IllegalArgumentException(
    issues.joinToString("\n") { "${it.path}：${it.message}" }
)

object RuleCodec {
    val json: Json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    /** Untrusted JSON value: preflight bytes, depth, nodes and duplicate keys before parsing. */
    fun decodeJsonValue(text: String): JsonElement = StrictJsonInput.parse(text).also { value ->
        EngineLimits.valueIssue(value)?.let { throw IllegalArgumentException(it) }
    }
    fun encodeRule(rule: Rule): String {
        val issues = EngineLimits.structure(rule)
        if (issues.isNotEmpty()) throw RuleValidationException(issues)
        return bounded(json.encodeToString(rule))
    }
    private fun bounded(text: String): String {
        StrictJsonInput.parse(text)
        return text
    }
    fun decodeRule(text: String): Rule {
        val element = StrictJsonInput.parse(text)
        StrictJsonInput.shape(element, bundle = false)
        val rule = json.decodeFromJsonElement<Rule>(element)
        val issues = EngineLimits.structure(rule)
        if (issues.isNotEmpty()) throw RuleValidationException(issues)
        return rule
    }
    fun exportBundle(rules: List<Rule>, globals: List<Variable> = emptyList()): String =
        bounded(json.encodeToString(checked(RuleBundle(rules = rules, globals = globals))))
    fun decodeBundle(text: String): RuleBundle {
        val obj = StrictJsonInput.parse(text) as? JsonObject ?: throw IllegalArgumentException("根节点必须是规则或 bundle 对象")
        val bundle = "rules" in obj || "formatVersion" in obj
        StrictJsonInput.shape(obj, bundle)
        return checked(if (bundle) json.decodeFromJsonElement<RuleBundle>(obj)
            else RuleBundle(rules = listOf(json.decodeFromJsonElement<Rule>(obj))))
    }
    private fun checked(bundle: RuleBundle): RuleBundle {
        val issues = mutableListOf<ValidationIssue>()
        if (bundle.formatVersion != 1) issues += ValidationIssue("formatVersion", "未知导入格式版本：${bundle.formatVersion}")
        if (bundle.rules.size > EngineLimits.MAX_RULES) issues += ValidationIssue("rules", "规则数量超过 ${EngineLimits.MAX_RULES}")
        if (bundle.globals.size > EngineLimits.MAX_VARIABLES) issues += ValidationIssue("globals", "全局变量数量超过 ${EngineLimits.MAX_VARIABLES}")
        if (issues.isNotEmpty()) throw RuleValidationException(issues)
        val ids = mutableSetOf<String>()
        bundle.rules.forEachIndexed { i, rule ->
            if (!ids.add(rule.id)) issues += ValidationIssue("rules[$i].id", "规则 ID 重复：${rule.id}")
            issues += RuleValidator.validate(rule).map { it.copy(path = "rules[$i].${it.path}") }
        }
        val names = mutableSetOf<String>()
        bundle.globals.forEachIndexed { i, variable ->
            if (!names.add(variable.name)) issues += ValidationIssue("globals[$i].name", "全局变量重名：${variable.name}")
            issues += RuleValidator.validateVariable(variable).map { it.copy(path = "globals[$i].${it.path}") }
        }
        if (issues.isNotEmpty()) throw RuleValidationException(issues)
        val byId = bundle.rules.associateBy { it.id }
        bundle.rules.forEachIndexed { i, rule ->
            visit(rule) { c, p ->
                if (c.type == "action.call_manual") {
                    val target = (c.params["ruleId"] as? JsonPrimitive)?.content?.let(byId::get)
                    if (target != null && target.type != "manual") issues += ValidationIssue("rules[$i].$p.params.ruleId", "调用目标必须是一键指令")
                }
            }
        }
        if (issues.isNotEmpty()) throw RuleValidationException(issues)
        return bundle
    }
    private fun visit(rule: Rule, block: (Component, String) -> Unit) {
        fun walk(components: List<Component>, path: String) {
            components.forEachIndexed { i, c ->
                val p = "$path[$i]"
                block(c, p)
                walk(c.children, "$p.children"); walk(c.elseActions, "$p.elseActions")
            }
        }
        walk(rule.triggers, "triggers"); walk(rule.conditions, "conditions"); walk(rule.actions, "actions")
    }
    fun prepareImport(bundle: RuleBundle): RuleBundle {
        checked(bundle)
        val used = bundle.rules.map { it.id }.toMutableSet()
        val remap = bundle.rules.associate { rule ->
            var id: String
            do { id = UUID.randomUUID().toString() } while (!used.add(id))
            rule.id to id
        }
        fun remapComponent(c: Component): Component {
            val oldId = (c.params["ruleId"] as? JsonPrimitive)?.content
            val params = if (c.type == "action.call_manual" && oldId in remap) c.params + ("ruleId" to JsonPrimitive(remap.getValue(oldId!!))) else c.params
            return c.copy(params = params, children = c.children.map(::remapComponent), elseActions = c.elseActions.map(::remapComponent))
        }
        return bundle.copy(rules = bundle.rules.map { rule ->
            rule.copy(id = remap.getValue(rule.id), enabled = false, triggers = rule.triggers.map(::remapComponent), conditions = rule.conditions.map(::remapComponent), actions = rule.actions.map(::remapComponent))
        })
    }
    fun scriptWarnings(bundle: RuleBundle): List<String> = buildList {
        checked(bundle)
        bundle.rules.forEachIndexed { i, rule ->
            visit(rule) { component, path ->
                val codeKeys = ComponentRegistry.find(component.type)?.params?.filter { it.kind == "code" }?.map { it.key }.orEmpty().toSet()
                val context = "rules[$i] ${rule.name} / $path / ${component.type} / root=${component.params["root"] ?: false} / backend=${component.params["backend"] ?: JsonPrimitive("auto")}"
                fun inspect(value: JsonElement, p: String, key: String) {
                    when (value) {
                        is JsonObject -> value.forEach { (k, v) -> inspect(v, "$p.$k", k) }
                        is JsonArray -> value.forEachIndexed { index, v -> inspect(v, "$p[$index]", key) }
                        is JsonPrimitive -> if (value.isString && (key in codeKeys || key in setOf("cmd", "code", "expression", "script", "javascript", "shell"))) {
                            add("脚本需二次确认：$context / $p\n${value.content}")
                        }
                    }
                }
                component.params.forEach { (key, value) -> inspect(value, "params.$key", key) }
            }
        }
    }
}
