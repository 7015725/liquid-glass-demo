package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2: the "..." menu must reorder and reparent steps without touching their contents. */
class ActionMoveTest {
    private fun toast(id: Long, text: String) =
        CommandItem(id = id, name = "Toast 提示", parameter = text, ic = Ic.Bell, typeId = "action.toast")

    private fun blockIf(id: Long, children: List<CommandItem>) =
        CommandItem(
            id = id, name = "如果", parameter = "", ic = Ic.Puzzle, typeId = "block.if",
            children = children, conditions = listOf(toast(90, "条件")),
        )

    private fun tree() = listOf(toast(1, "A"), blockIf(2, listOf(toast(3, "B"))), toast(4, "C"))

    private fun host(item: CommandItem) =
        item.typeId == "block.if" || item.typeId == "block.loop"

    @Test
    fun upAndDownSwapNeighbours() {
        val up = tree().moveItem(4, -1)
        assertEquals(listOf(1L, 4L, 2L), up.map { it.id })
        val down = tree().moveItem(1, 1)
        assertEquals(listOf(2L, 1L, 4L), down.map { it.id })
    }

    @Test
    fun moveIntoPreviousBlockAppendsAsLastChild() {
        val moved = tree().moveIntoPreviousBlock(4, ::host)
        assertEquals(listOf(1L, 2L), moved.map { it.id })
        assertEquals(listOf(3L, 4L), moved[1].children.map { it.id })
    }

    @Test
    fun moveOutOfBlockPlacesStepAfterItsBlock() {
        val moved = tree().moveOutOfBlock(3)
        assertEquals(listOf(1L, 2L, 3L, 4L), moved.map { it.id })
        assertTrue(moved[1].children.isEmpty())
    }

    @Test
    fun moveOutLeavesRootLevelAlone() {
        assertEquals(tree(), tree().moveOutOfBlock(1))
    }

    @Test
    fun moveIntoNonContainerIsRefused() {
        assertEquals(tree(), tree().moveIntoPreviousBlock(2, ::host))
        assertFalse(tree().canMoveIntoPreviousBlock(2, ::host))
        assertTrue(tree().canMoveIntoPreviousBlock(4, ::host))
    }
}
