package com.flowglass.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class ValidationIssue(val path: String, val message: String)

object RuleValidator {
    private const val MAX_BLOCK_DEPTH = 32
    private const val MAX_CALL_DEPTH = 5

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
        rule.actions.forEachIndexed { index, component ->
            validateComponent("actions[$index]", component, ComponentKind.ACTION, registry, ::add)
            validateNested("actions[$index]", component, registry, insideLoop = false, depth = 1, addIssue = ::add)
        }
        rule.locals.forEachIndexed { index, local ->
            if (local.name.isBlank()) add(ValidationIssue("locals[$index].name", "变量名不能为空"))
            if (!Regex("[A-Za-z0-9_-]+").matches(local.name)) add(ValidationIssue("locals[$index].name", "变量名只能包含字母、数字、_ -"))
        }
        rule.triggers.forEachIndexed { index, component ->
            if (component.children.isNotEmpty() || component.branches.isNotEmpty()) {
                add(ValidationIssue("triggers[$index]", "触发器不能包含块"))
            }
        }
    }

    /**
     * Whole-document checks that a single rule cannot answer: call cycles, missing targets and
     * the nesting depth limit.
     */
    fun validateDocument(document: RuleDocument, registry: ComponentRegistry? = null): List<ValidationIssue> = buildList {
        document.rules.forEachIndexed { index, rule ->
            validate(rule, registry).forEach { add(ValidationIssue("rules[$index].${it.path}", it.message)) }
        }
        val names = document.rules.map { it.name.trim() }
        names.groupBy { it }.filter { (name, group) -> name.isNotEmpty() && group.size > 1 }
            .forEach { (name, _) -> add(ValidationIssue("rules", "指令名称重复，串联时只会找到第一条：$name")) }

        val byName = document.rules.associateBy { it.name.trim() }
        val edges = document.rules.associate { rule ->
            rule.name.trim() to callsIn(rule.actions).map { it.first }.distinct()
        }
        for ((from, targets) in edges) for (target in targets) {
            if (target !in byName) add(ValidationIssue("rules", "「$from」调用的指令不存在：$target"))
        }

        val state = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()
        fun visit(name: String) {
            when (state[name]) {
                1 -> {
                    val cycle = stack.dropWhile { it != name } + name
                    add(ValidationIssue("rules", "指令调用成环：${cycle.joinToString(" → ")}"))
                    return
                }
                2 -> return
            }
            state[name] = 1
            stack += name
            for (target in edges[name].orEmpty()) if (target in byName) visit(target)
            stack.removeAt(stack.lastIndex)
            state[name] = 2
        }
        for (name in byName.keys) if (state[name] == null) visit(name)

        fun longest(name: String, seen: MutableSet<String>): Int {
            if (!seen.add(name)) return 0
            val deepest = edges[name].orEmpty().filter { it in byName }.maxOfOrNull { longest(it, seen) } ?: 0
            seen.remove(name)
            return deepest + 1
        }
        for (name in byName.keys) {
            val depth = longest(name, mutableSetOf())
            if (depth - 1 > MAX_CALL_DEPTH) add(ValidationIssue("rules", "调用层级超过 $MAX_CALL_DEPTH 层：$name"))
        }
    }

    private fun callsIn(nodes: List<RuleComponent>): List<Pair<String, RuleComponent>> {
        val result = mutableListOf<Pair<String, RuleComponent>>()
        fun walk(component: RuleComponent) {
            if (component.type == "action.run_rule") {
                val target = (component.params["rule"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (target.isNotEmpty()) result += target to component
            }
            component.children.forEach(::walk)
            component.branches.forEach { branch -> branch.children.forEach(::walk) }
        }
        nodes.forEach(::walk)
        return result
    }

    private fun validateNested(
        path: String,
        component: RuleComponent,
        registry: ComponentRegistry?,
        insideLoop: Boolean,
        depth: Int,
        addIssue: (ValidationIssue) -> Unit,
    ) {
        if (depth > MAX_BLOCK_DEPTH) {
            addIssue(ValidationIssue(path, "块嵌套超过 $MAX_BLOCK_DEPTH 层"))
            return
        }
        val meta = registry?.find(component.type)
        val block = meta?.block
        val isContainer = block == BlockKind.IF || block == BlockKind.LOOP
        if (!isContainer && (component.children.isNotEmpty() || component.branches.isNotEmpty())) {
            addIssue(ValidationIssue(path, "「${meta?.name ?: component.type}」不能包含子步骤"))
        }
        if (component.onError == OnError.RETRY && block != null) {
            addIssue(ValidationIssue("$path.onError", "块级错误处理只支持停止或继续，重试请设在普通步骤上"))
        }
        when (block) {
            BlockKind.IF -> {
                if (component.conditions.isEmpty()) addIssue(ValidationIssue("$path.conditions", "如果块缺少条件"))
                val elseCount = component.branches.count { it.conditions.isEmpty() }
                if (elseCount > 1) addIssue(ValidationIssue("$path.branches", "最多只能有一个否则分支"))
                if (component.branches.dropLast(elseCount).any { it.conditions.isEmpty() }) {
                    addIssue(ValidationIssue("$path.branches", "否则如果缺少条件"))
                }
                if (component.children.isEmpty() && component.branches.all { it.children.isEmpty() }) {
                    addIssue(ValidationIssue("$path.children", "如果块没有任何步骤"))
                }
            }
            BlockKind.LOOP -> {
                val mode = (component.params["mode"] as? JsonPrimitive)?.content ?: "times"
                if (mode == "while" && component.conditions.isEmpty()) {
                    addIssue(ValidationIssue("$path.conditions", "条件循环缺少条件"))
                }
                if (mode != "while") {
                    val times = (component.params["times"] as? JsonPrimitive)?.content?.toIntOrNull()
                    if (times == null || times !in 1..1000) addIssue(ValidationIssue("$path.params.times", "循环次数需在 1–1000"))
                }
                if (component.children.isEmpty()) addIssue(ValidationIssue("$path.children", "循环体为空"))
            }
            BlockKind.WAIT -> {
                if (component.conditions.isEmpty()) addIssue(ValidationIssue("$path.conditions", "等待块缺少条件"))
                val timeout = (component.params["timeout"] as? JsonPrimitive)?.content?.toLongOrNull()
                if (timeout == null || timeout !in 1_000..3_600_000) {
                    addIssue(ValidationIssue("$path.params.timeout", "超时需在 1 秒到 1 小时之间"))
                }
                val poll = (component.params["poll"] as? JsonPrimitive)?.content?.toLongOrNull()
                if (poll != null && poll !in 250..5_000) {
                    addIssue(ValidationIssue("$path.params.poll", "轮询间隔需在 250 毫秒到 5 秒之间"))
                }
            }
            BlockKind.BREAK -> if (!insideLoop) addIssue(ValidationIssue(path, "跳出循环只能放在循环内"))
            BlockKind.RUN_RULE -> {
                val target = (component.params["rule"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (target.isEmpty()) addIssue(ValidationIssue("$path.params.rule", "未选择目标指令"))
            }
            else -> Unit
        }
        if (component.conditions.isNotEmpty() && block !in setOf(BlockKind.IF, BlockKind.LOOP, BlockKind.WAIT)) {
            if (block != null) addIssue(ValidationIssue("$path.conditions", "该块不支持条件"))
        }
        component.children.forEachIndexed { index, child ->
            validateComponent("$path.children[$index]", child, ComponentKind.ACTION, registry, addIssue)
            validateNested("$path.children[$index]", child, registry, insideLoop || block == BlockKind.LOOP, depth + 1, addIssue)
        }
        component.branches.forEachIndexed { branchIndex, branch ->
            branch.conditions.forEachIndexed { index, condition ->
                validateComponent("$path.branches[$branchIndex].conditions[$index]", condition, ComponentKind.CONDITION, registry, addIssue)
            }
            branch.children.forEachIndexed { index, child ->
                val childPath = "$path.branches[$branchIndex].children[$index]"
                validateComponent(childPath, child, ComponentKind.ACTION, registry, addIssue)
                validateNested(childPath, child, registry, insideLoop, depth + 1, addIssue)
            }
        }
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
            if (component.params[param.key] == null || component.params[param.key] == JsonNull) addIssue(ValidationIssue("$path.params.${param.key}", "缺少必填参数"))
        }
    }
}
