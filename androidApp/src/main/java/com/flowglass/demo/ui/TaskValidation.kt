package com.flowglass.demo.ui

import com.flowglass.engine.BlockKind
import com.flowglass.engine.BuiltInComponents

/**
 * C2 编辑期校验：块是否完整、调用目标是否存在、是否成环。
 * 保存路径仍由 RuleValidator 兜底，这里只负责在界面上提前说清楚。
 */
internal data class ValidationIssue(val ownerId: Long?, val text: String)

internal fun AppState.validationIssues(cmd: Cmd): List<String> = validationItems(cmd).map { it.text }

/** Validation entries keep the owning block id so a block card can show its own count. */
internal fun AppState.validationItems(cmd: Cmd): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    fun issue(ownerId: Long?, text: String) { issues += ValidationIssue(ownerId, text) }
    val titles = (autoCmds + oneTaps).map { it.title.trim() }

    fun loopTimes(item: CommandItem): Int? =
        item.parameter.split('|').getOrNull(1)?.trim()?.toIntOrNull()

    fun walk(items: List<CommandItem>, loopDepth: Int) {
        for (item in items) {
            val block = item.typeId?.let { BuiltInComponents.registry().find(it)?.block }
            when (block) {
                BlockKind.IF -> {
                    if (item.conditions.isEmpty()) issue(item.id, "「${item.name}」缺少条件")
                    if (item.children.isEmpty() && item.branches.all { it.children.isEmpty() }) {
                        issue(item.id, "「${item.name}」还没有任何步骤")
                    }
                    item.branches.forEachIndexed { index, branch ->
                        if (branch.conditions.isEmpty() && index < item.branches.lastIndex) {
                            issue(item.id, "「${item.name}」的第 ${index + 1} 个否则如果缺少条件")
                        }
                    }
                }
                BlockKind.LOOP -> {
                    val byTimes = item.parameter.contains("按次数")
                    if (byTimes) {
                        val times = loopTimes(item)
                        if (times == null || times <= 0) issue(item.id, "「${item.name}」的次数无效")
                        if (times != null && times > 1000) issue(item.id, "「${item.name}」最多循环 1000 次")
                    } else if (item.conditions.isEmpty()) {
                        issue(item.id, "「${item.name}」缺少循环条件")
                    }
                    if (item.children.isEmpty()) issue(item.id, "「${item.name}」循环体为空")
                }
                BlockKind.WAIT -> if (item.conditions.isEmpty()) issue(item.id, "「${item.name}」缺少等待条件")
                BlockKind.BREAK -> if (loopDepth == 0) issue(item.id, "「${item.name}」不在循环内")
                BlockKind.RUN_RULE -> {
                    val target = item.parameter.substringBefore('|').trim()
                    when {
                        target.isEmpty() -> issue(item.id, "「${item.name}」还没有选择目标指令")
                        titles.none { it == target } -> issue(item.id, "找不到目标指令「$target」")
                    }
                }
                else -> Unit
            }
            walk(item.children, loopDepth + if (block == BlockKind.LOOP) 1 else 0)
            item.branches.forEach { walk(it.children, loopDepth) }
        }
    }

    walk(cmd.actions, 0)

    // 以当前指令为起点沿 run_rule 走一遍，发现回到自己即认为成环。
    val graph = mutableMapOf<String, List<String>>()
    (autoCmds + oneTaps).forEach { command ->
        val targets = mutableListOf<String>()
        fun collect(items: List<CommandItem>) {
            items.forEach { item ->
                if (item.typeId == "action.run_rule") {
                    val target = item.parameter.substringBefore('|').trim()
                    if (target.isNotEmpty()) targets += target
                }
                collect(item.children)
                item.branches.forEach { collect(it.children) }
            }
        }
        collect(command.actions)
        graph[command.title.trim()] = targets
    }
    val start = cmd.title.trim()
    val stack = ArrayDeque<String>()
    val visiting = mutableSetOf<String>()
    fun findCycle(node: String): List<String>? {
        if (node == start && stack.isNotEmpty()) return stack.toList() + node
        if (!visiting.add(node)) return null
        stack.addLast(node)
        for (next in graph[node].orEmpty()) {
            findCycle(next)?.let { return it }
        }
        stack.removeLast()
        visiting.remove(node)
        return null
    }
    findCycle(start)?.let { path ->
        if (issues.none { it.text.contains("成环") }) issue(null, "指令调用成环：${path.joinToString(" → ")}")
    }
    return issues
}

/** Issues owned by [blockId] or any nested block, in the same order as the global banner. */
internal fun AppState.blockValidationIssues(cmd: Cmd, blockId: Long): List<String> {
    val block = cmd.actions.findActionNode(blockId) ?: return emptyList()
    val ids = buildSet {
        fun add(item: CommandItem) {
            add(item.id)
            item.children.forEach(::add)
            item.branches.forEach { branch -> branch.children.forEach(::add) }
        }
        add(block)
    }
    return validationItems(cmd).filter { it.ownerId in ids }.map { it.text }
}
