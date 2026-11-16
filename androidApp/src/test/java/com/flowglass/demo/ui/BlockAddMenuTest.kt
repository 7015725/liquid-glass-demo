package com.flowglass.demo.ui

import com.flowglass.engine.BlockKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The block footer menu must expose only the additions valid for that block/mode. */
class BlockAddMenuTest {
    private fun labels(
        block: BlockKind?,
        timesLoop: Boolean,
        branches: List<CommandBranch> = emptyList(),
    ): List<String> = blockAddActions(block, timesLoop, branches).map { it.label }

    @Test
    fun ifMenuListsStepsConditionsAndOnlyTheMissingElse() {
        assertEquals(listOf("步骤", "条件", "否则"), labels(BlockKind.IF, timesLoop = false))
        assertEquals(
            listOf("步骤", "条件"),
            labels(BlockKind.IF, timesLoop = false, branches = listOf(CommandBranch())),
        )
    }

    @Test
    fun loopAndWaitMenusFollowTheirModes() {
        assertEquals(listOf("步骤", "条件"), labels(BlockKind.LOOP, timesLoop = false))
        assertEquals(listOf("步骤"), labels(BlockKind.LOOP, timesLoop = true))
        assertEquals(listOf("条件"), labels(BlockKind.WAIT, timesLoop = false))
    }

    @Test
    fun branchMenuKeepsOnlyItsOwnAdditions() {
        // A branch's conditions are edited in the branch header; its body only accepts steps.
        // One entry means BlockAddButton runs the action directly with no popup.
        assertEquals(listOf("步骤"), branchAddActions().map { it.label })
    }
}
