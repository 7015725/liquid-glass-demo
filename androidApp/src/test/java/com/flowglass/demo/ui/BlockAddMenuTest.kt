package com.flowglass.demo.ui

import com.flowglass.engine.BlockKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The block footer menu must expose only the additions valid for that block/mode. */
class BlockAddMenuTest {
    @Test
    fun ifMenuListsStepsConditionsAndOnlyTheMissingElse() {
        assertEquals(
            listOf("步骤", "条件", "否则"),
            blockAddMenuLabels(BlockKind.IF, timesLoop = false, branches = emptyList()),
        )
        assertEquals(
            listOf("步骤", "条件"),
            blockAddMenuLabels(BlockKind.IF, timesLoop = false, branches = listOf(CommandBranch())),
        )
    }

    @Test
    fun loopAndWaitMenusFollowTheirModes() {
        assertEquals(
            listOf("步骤", "条件"),
            blockAddMenuLabels(BlockKind.LOOP, timesLoop = false, branches = emptyList()),
        )
        assertEquals(
            listOf("步骤"),
            blockAddMenuLabels(BlockKind.LOOP, timesLoop = true, branches = emptyList()),
        )
        assertEquals(
            listOf("条件"),
            blockAddMenuLabels(BlockKind.WAIT, timesLoop = false, branches = emptyList()),
        )
    }

    @Test
    fun branchMenuKeepsOnlyItsOwnAdditions() {
        assertEquals(listOf("步骤"), branchAddMenuLabels(hasConditions = false))
        assertEquals(listOf("步骤"), branchAddMenuLabels(hasConditions = true))
    }
}
