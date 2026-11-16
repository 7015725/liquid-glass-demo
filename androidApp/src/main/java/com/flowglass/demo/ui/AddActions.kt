package com.flowglass.demo.ui

import com.flowglass.engine.BlockKind

/** One item offered by an "add" affordance. A single item is executed directly, never popup. */
internal data class AddMenuEntry(val label: String, val action: () -> Unit)

internal enum class AddActionKind { STEP, CONDITION, ELSE }
internal data class AddAction(val label: String, val kind: AddActionKind)

/**
 * The only place that decides which additions a block or branch accepts. UI and tests both use
 * this model so a one-entry result can be collapsed into a direct action consistently.
 */
internal fun blockAddActions(
    block: BlockKind?,
    timesLoop: Boolean,
    branches: List<CommandBranch>,
): List<AddAction> {
    if (block == BlockKind.WAIT) return listOf(AddAction("条件", AddActionKind.CONDITION))
    return buildList {
        add(AddAction("步骤", AddActionKind.STEP))
        if (!timesLoop) add(AddAction("条件", AddActionKind.CONDITION))
        if (block == BlockKind.IF && branches.none { it.conditions.isEmpty() }) {
            add(AddAction("否则", AddActionKind.ELSE))
        }
    }
}

/**
 * Branch bodies only hold steps: a branch's own condition list is edited from the branch header,
 * not from the trailing add button. Keeping this a one-item policy makes the button a direct
 * action instead of a needless single-entry popup.
 */
internal fun branchAddActions(): List<AddAction> = listOf(AddAction("步骤", AddActionKind.STEP))
