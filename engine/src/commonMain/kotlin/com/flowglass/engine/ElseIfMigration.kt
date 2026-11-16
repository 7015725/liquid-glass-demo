package com.flowglass.engine

/**
 * Converts an if-block with old-style else-if branches into the nested-if representation used by
 * the editor:
 *
 * `if A then X else if B then Y else Z`
 * -> `if A then X else (if B then Y else Z)`
 *
 * The engine can still execute conditioned branches directly; this conversion is for the editor's
 * three-section if page. It is pure and preserves component params/conditions/children.
 */
fun RuleComponent.normalizeElseIfBranches(): RuleComponent {
    fun normalizeBranch(branch: RuleBranch): RuleBranch = RuleBranch(
        conditions = branch.conditions.map { it.normalizeElseIfBranches() },
        children = branch.children.map { it.normalizeElseIfBranches() },
    )

    val normalized = copy(
        children = children.map { it.normalizeElseIfBranches() },
        branches = branches.map(::normalizeBranch),
        conditions = conditions.map { it.normalizeElseIfBranches() },
    )
    if (normalized.type != "block.if") return normalized
    return normalized.copy(branches = normalized.branches.normalizedIfChain())
}

/**
 * If [branches] starts with one or more conditioned branches, wrap the first one as the body of
 * the new else branch and recurse into the remaining chain. A plain else (no conditions) stops the
 * conversion because it is already the terminal branch.
 */
private fun List<RuleBranch>.normalizedIfChain(): List<RuleBranch> {
    val firstElseIf = indexOfFirst { it.conditions.isNotEmpty() }
    if (firstElseIf < 0) return this
    // Invalid ordering: an else before a later else-if. Keep the original tree rather than
    // silently deleting the conditioned branch.
    if (take(firstElseIf).any { it.conditions.isEmpty() }) return this
    val first = this[firstElseIf]
    val nested = RuleComponent(
        type = "block.if",
        children = first.children,
        branches = drop(firstElseIf + 1).normalizedIfChain(),
        conditions = first.conditions,
    )
    return take(firstElseIf) + RuleBranch(children = listOf(nested))
}
