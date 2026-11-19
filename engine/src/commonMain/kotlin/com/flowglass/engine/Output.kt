package com.flowglass.engine

import com.flowglass.engine.methodhook.AppMethodCall
import com.flowglass.engine.methodhook.MethodHookNames
import kotlinx.serialization.Serializable

/**
 * One output a component can publish. Specs live in component metadata only; they are never
 * persisted with a rule. The stable [key] is what the runtime writes; [defaultName] is only the
 * starting point for the instance-level name chosen in the rule editor.
 */
@Serializable
data class OutputSpec(
    val key: String,
    val label: String,
    val type: VariableType,
    val defaultName: String,
    val description: String = "",
)

/** One instance-level output name stored on a rule component. The same key may have aliases. */
@Serializable
data class OutputBinding(
    val key: String,
    val name: String,
    val enabled: Boolean = true,
)

/**
 * Derives the complete output schema of a component: the generic rows every trigger/action
 * gets, then the specific rows declared by its metadata. Conditions have no outputs.
 */
object OutputCatalog {
    val triggerGeneric: List<OutputSpec> = listOf(
        OutputSpec("trigger.firedAt", "触发时间", VariableType.TEXT, "触发时间",
            "本次触发的时间（HH:mm）"),
        OutputSpec("trigger.reason", "触发原因", VariableType.TEXT, "触发原因",
            "触发来源的说明"),
        OutputSpec("trigger.name", "触发器名称", VariableType.TEXT, "触发器名称",
            "触发器组件名称"),
    )

    val actionGeneric: List<OutputSpec> = listOf(
        OutputSpec("action.status", "动作状态", VariableType.TEXT, "动作状态",
            "成功、失败或跳过"),
        OutputSpec("action.result", "动作结果", VariableType.TEXT, "动作结果",
            "动作返回的说明"),
        OutputSpec("action.name", "动作名称", VariableType.TEXT, "动作名称",
            "动作组件名称"),
    )

    fun genericFor(kind: ComponentKind): List<OutputSpec> = when (kind) {
        ComponentKind.TRIGGER -> triggerGeneric
        ComponentKind.ACTION -> actionGeneric
        ComponentKind.CONDITION -> emptyList()
    }

    /** Specific declarations first, then the generic rows; keys are unique. */
    fun allOutputs(meta: ComponentMeta): List<OutputSpec> {
        if (meta.kind == ComponentKind.CONDITION) return emptyList()
        return (meta.outputs + genericFor(meta.kind)).distinctBy { it.key }
    }

    fun spec(meta: ComponentMeta, key: String): OutputSpec? = allOutputs(meta).firstOrNull { it.key == key }

    fun defaultBindings(meta: ComponentMeta): List<OutputBinding> =
        allOutputs(meta).map { OutputBinding(it.key, it.defaultName) }

    /**
     * Bindings that are effective at runtime. A component saved before this model existed has no
     * bindings; it falls back to the declared defaults instead of publishing nothing.
     */
    fun effectiveBindings(outputs: List<OutputBinding>, meta: ComponentMeta): List<OutputBinding> =
        if (outputs.isEmpty()) defaultBindings(meta) else outputs

    fun effectiveBindings(component: RuleComponent, meta: ComponentMeta): List<OutputBinding> =
        effectiveBindings(component.outputs, meta)

    fun enabledNames(component: RuleComponent, meta: ComponentMeta): Set<String> =
        effectiveBindings(component, meta).filter { it.enabled }.mapTo(linkedSetOf()) { it.name }

    /**
     * Allocates a rule-unique name from a label. Invalid characters are replaced rather than
     * rejected so a metadata label can never make a component unsaveable.
     */
    fun allocateName(raw: String, used: MutableSet<String>, prefix: String = "输出"): String {
        val base = sanitizeName(raw).ifEmpty { sanitizeName(prefix).ifEmpty { "输出" } }
        if (used.add(base)) return base
        var index = 2
        while (index <= 10_000) {
            val candidate = base + index
            if (used.add(candidate)) return candidate
            index++
        }
        val fallback = sanitizeName(prefix).ifEmpty { "输出" }
        val stem = (fallback + base).take(56)
        index = 2
        while (index <= 10_000) {
            val candidate = stem + index
            if (used.add(candidate)) return candidate
            index++
        }
        throw IllegalStateException("无法分配输出变量名")
    }

    private fun sanitizeName(raw: String): String {
        val cleaned = raw.trim().map { char ->
            if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_'
        }.joinToString("").trim('_')
        if (cleaned.isEmpty()) return ""
        val limited = cleaned.take(60)
        return if (limited[0].isLetter() || limited[0] == '_') limited else "_$limited"
    }

    /**
     * Builds default bindings for a new component so two instances never start with the same
     * name. [used] should contain every name already visible to the rule.
     */
    fun bindingsForNew(meta: ComponentMeta, used: Set<String>): List<OutputBinding> {
        val taken = used.toMutableSet()
        return allOutputs(meta).map { spec ->
            OutputBinding(spec.key, allocateName(spec.defaultName, taken, meta.name))
        }
    }

    /**
     * Repairs one rule: drops unknown keys, fills newly declared outputs, renames conflicts and
     * clears condition bindings. Existing names are preserved when possible.
     */
    fun normalizeRule(
        rule: Rule,
        registry: ComponentRegistry = BuiltInComponents.registry(),
        reserved: Set<String> = emptySet(),
    ): Rule {
        val used = linkedSetOf<String>()
        used += BuiltInVariables.all.map { it.name }
        used += rule.locals.map { it.name }
        used += reserved
        rule.triggers.forEach { trigger ->
            used += methodExtractionNames(trigger.type, trigger.params)
        }

        fun normalizeComponent(component: RuleComponent, forceCondition: Boolean): RuleComponent {
            val meta = registry.find(component.type)
            val conditions = component.conditions.map { normalizeComponent(it, true) }
            val children = component.children.map { normalizeComponent(it, false) }
            val branches = component.branches.map { branch ->
                branch.copy(
                    conditions = branch.conditions.map { normalizeComponent(it, true) },
                    children = branch.children.map { normalizeComponent(it, false) },
                )
            }
            val outputs = when {
                forceCondition || meta == null || meta.kind == ComponentKind.CONDITION -> emptyList()
                else -> {
                    val specs = allOutputs(meta)
                    val known = specs.map { it.key }.toSet()
                    val existing = component.outputs.filter { it.key in known }
                    val result = mutableListOf<OutputBinding>()
                    for (spec in specs) {
                        val candidates = existing.filter { it.key == spec.key }
                        if (candidates.isEmpty()) {
                            result += OutputBinding(
                                spec.key,
                                allocateName(spec.defaultName.ifBlank { spec.label }, used, meta.name),
                            )
                        } else {
                            candidates.forEach { binding ->
                                val name = if (VariableName.isValid(binding.name) && used.add(binding.name)) {
                                    binding.name
                                } else {
                                    allocateName(
                                        binding.name.ifBlank { spec.defaultName.ifBlank { spec.label } },
                                        used,
                                        meta.name,
                                    )
                                }
                                result += binding.copy(name = name)
                            }
                        }
                    }
                    result
                }
            }
            return component.copy(outputs = outputs, children = children, branches = branches, conditions = conditions)
        }

        return rule.copy(
            triggers = rule.triggers.map { normalizeComponent(it, false) },
            conditions = rule.conditions.map { normalizeComponent(it, true) },
            actions = rule.actions.map { normalizeComponent(it, false) },
        )
    }

    fun normalizeDocument(
        document: RuleDocument,
        registry: ComponentRegistry = BuiltInComponents.registry(),
    ): RuleDocument {
        val reserved = document.globals.keys
        return document.copy(rules = document.rules.map { normalizeRule(it, registry, reserved) })
    }

    /** Names of user-defined extraction fields on an app-method-call trigger. */
    fun methodExtractionNames(typeId: String, params: Map<String, kotlinx.serialization.json.JsonElement>): Set<String> {
        if (typeId != AppMethodCall.TYPE_ID) return emptySet()
        val raw = (params[AppMethodCall.KEY_EXTRACTIONS] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content.orEmpty()
        return runCatching { AppMethodCall.decodeExtractions(raw) }.getOrDefault(emptyList())
            .map { MethodHookNames.normalizeExtractionName(it.name) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** Local schema checks; cross-component/rule checks live in RuleValidator and the editor. */
    fun validateBindings(meta: ComponentMeta, outputs: List<OutputBinding>): List<String> {
        val issues = mutableListOf<String>()
        val specs = allOutputs(meta).associateBy { it.key }
        if (outputs.isEmpty()) return issues
        val seen = mutableSetOf<String>()
        for (binding in outputs) {
            if (binding.key !in specs) issues += "未知输出：${binding.key}"
            if (!VariableName.isValid(binding.name)) {
                issues += VariableName.error(binding.name) ?: "输出变量名无效：${binding.name}"
            } else if (!seen.add(binding.name)) {
                issues += "输出变量名重复：${binding.name}"
            }
        }
        if (outputs.none { it.enabled }) issues += "「${meta.name}」至少要启用一个输出"
        return issues
    }
}

/** Type conversion shared by the runtime and tests. A null result means "do not publish". */
object OutputValues {
    fun convert(spec: OutputSpec, value: Any?): Any? {
        if (value == null) return null
        return runCatching {
            when (spec.type) {
                VariableType.INT -> VariableValues.parseInt(value)
                VariableType.BOOLEAN -> VariableValues.parseBoolean(value)
                VariableType.TEXT -> VariableValues.text(value)
                VariableType.INT_LIST -> VariableValues.parse(VariableValues.text(value), VariableType.INT_LIST)
                VariableType.BOOLEAN_LIST -> VariableValues.parse(VariableValues.text(value), VariableType.BOOLEAN_LIST)
                VariableType.TEXT_LIST -> VariableValues.parse(VariableValues.text(value), VariableType.TEXT_LIST)
            }
        }.getOrNull()
    }
}
