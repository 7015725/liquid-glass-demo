package com.flowglass.demo.ui

/**
 * UI-side fallback for commands constructed directly (tests, legacy in-memory snapshots).
 * Normal file loads go through the engine normalizer before mapping; this
 * keeps a directly built old tree from exposing an else-if page.
 */
internal fun List<CommandItem>.normalizeUiElseIfBranches(nextId: () -> Long): List<CommandItem> =
    map { item ->
        val children = item.children.normalizeUiElseIfBranches(nextId)
        val conditions = item.conditions.normalizeUiElseIfBranches(nextId)
        val branches = item.branches.map { branch ->
            branch.copy(
                children = branch.children.normalizeUiElseIfBranches(nextId),
                conditions = branch.conditions.normalizeUiElseIfBranches(nextId),
            )
        }
        val normalized = item.copy(children = children, conditions = conditions, branches = branches)
        if (normalized.typeId == "block.if") normalized.normalizedUiIfBranches(nextId) else normalized
    }

private fun CommandItem.normalizedUiIfBranches(nextId: () -> Long): CommandItem {
    val firstElseIf = branches.indexOfFirst { it.conditions.isNotEmpty() }
    if (firstElseIf < 0) return this
    if (branches.take(firstElseIf).any { it.conditions.isEmpty() }) return this
    val first = branches[firstElseIf]
    val nested = CommandItem(
        id = nextId(), name = "如果", parameter = "", ic = Ic.Puzzle, typeId = "block.if",
        children = first.children,
        branches = branches.drop(firstElseIf + 1).normalizedUiChain(nextId),
        conditions = first.conditions,
    )
    return copy(branches = branches.take(firstElseIf) + CommandBranch(children = listOf(nested)))
}

private fun List<CommandBranch>.normalizedUiChain(nextId: () -> Long): List<CommandBranch> {
    val firstElseIf = indexOfFirst { it.conditions.isNotEmpty() }
    if (firstElseIf < 0) return this
    if (take(firstElseIf).any { it.conditions.isEmpty() }) return this
    val first = this[firstElseIf]
    val nested = CommandItem(
        id = nextId(), name = "如果", parameter = "", ic = Ic.Puzzle, typeId = "block.if",
        children = first.children,
        branches = drop(firstElseIf + 1).normalizedUiChain(nextId),
        conditions = first.conditions,
    )
    return take(firstElseIf) + CommandBranch(children = listOf(nested))
}
