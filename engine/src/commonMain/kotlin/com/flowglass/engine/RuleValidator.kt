package com.flowglass.engine

data class ValidationIssue(val path: String, val message: String)

object RuleValidator {
    fun validate(rule: Rule, registry: ComponentRegistry? = null): List<ValidationIssue> = buildList {
        if (rule.id.isBlank()) add(ValidationIssue("id", "规则标识不能为空"))
        if (rule.name.isBlank()) add(ValidationIssue("name", "规则名称不能为空"))
        if (rule.type == RuleType.MANUAL && (rule.triggers.isNotEmpty() || rule.conditions.isNotEmpty())) {
            add(ValidationIssue("type", "一键指令不能包含触发器或条件"))
        }
        validateMatch("triggerMatch", rule.triggerMatch, rule.triggers.size, addIssue = ::add)
        validateMatch("conditionMatch", rule.conditionMatch, rule.conditions.size, addIssue = ::add)
        rule.triggers.forEachIndexed { index, component -> validateComponent("triggers[$index]", component, ComponentKind.TRIGGER, registry, ::add) }
        rule.conditions.forEachIndexed { index, component -> validateComponent("conditions[$index]", component, ComponentKind.CONDITION, registry, ::add) }
        rule.actions.forEachIndexed { index, component -> validateComponent("actions[$index]", component, ComponentKind.ACTION, registry, ::add) }
    }

    private fun validateMatch(path: String, match: MatchSpec, count: Int, addIssue: (ValidationIssue) -> Unit) {
        if (match.windowSeconds < 0) addIssue(ValidationIssue("$path.windowSeconds", "有效窗口不能小于零"))
        if (match.mode == MatchMode.CUSTOM) {
            if ((match.atLeast == null) == (match.tree == null)) addIssue(ValidationIssue(path, "custom 必须且只能指定 atLeast 或 tree"))
            if (match.atLeast != null && (match.atLeast !in 1..count)) addIssue(ValidationIssue("$path.atLeast", "至少命中数量超出组件数"))
        }
    }

    private fun validateComponent(
        path: String,
        component: RuleComponent,
        expectedKind: ComponentKind,
        registry: ComponentRegistry?,
        addIssue: (ValidationIssue) -> Unit,
    ) {
        if (component.type.isBlank()) addIssue(ValidationIssue("$path.type", "组件 typeId 不能为空"))
        if (component.type == "legacy.unknown") return
        val meta = registry?.find(component.type)
        if (registry != null && meta == null) {
            addIssue(ValidationIssue("$path.type", "未注册的组件 typeId"))
            return
        }
        meta ?: return
        if (meta.kind != expectedKind) addIssue(ValidationIssue("$path.type", "组件类型与所在列表不匹配"))
        meta.params.filter { it.required }.forEach { param ->
            if (component.params[param.key] == null || component.params[param.key] == kotlinx.serialization.json.JsonNull) addIssue(ValidationIssue("$path.params.${param.key}", "缺少必填参数"))
        }
    }
}
