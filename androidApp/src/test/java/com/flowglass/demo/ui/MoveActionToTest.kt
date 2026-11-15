package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** alpha6 B: one move operation for root/body/branch targets, with strict validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MoveActionToTest {
    private fun step(id: Long, name: String = "步骤$id") =
        CommandItem(id = id, name = name, parameter = "", ic = Ic.Bell, typeId = "action.toast")

    private fun block(
        id: Long,
        type: String = "block.if",
        children: List<CommandItem> = emptyList(),
        branches: List<CommandBranch> = emptyList(),
        collapsed: Boolean = false,
    ) = CommandItem(
        id = id, name = if (type == "block.loop") "循环" else "如果", parameter = "",
        ic = Ic.Puzzle, typeId = type, children = children, branches = branches, collapsed = collapsed,
        conditions = listOf(step(90 + id, "条件$id")),
    )

    private fun tree(): List<CommandItem> = listOf(
        step(1, "A"),
        block(2, children = listOf(
            block(3, type = "block.loop", children = listOf(step(4, "深层"))),
        ), branches = listOf(
            CommandBranch(children = listOf(step(5, "否则步骤")), conditions = listOf(step(95))),
            CommandBranch(children = emptyList()),
        )),
        step(6, "B"),
        block(7, type = "block.loop", children = emptyList()),
        block(9, type = "block.if", children = listOf(step(10, "折叠内")), collapsed = true),
        block(8, type = "block.wait_until"),
    )

    private fun move(
        source: Long,
        parent: Long?,
        branch: Int?,
        index: Int,
        root: List<CommandItem> = tree(),
    ): List<CommandItem>? = root.moveActionTo(source, parent, branch, index)

    @Test
    fun movesThirdLevelToRoot() {
        val moved = move(4, null, null, 0)!!
        assertEquals(listOf(4L, 1L, 2L, 6L, 7L, 9L, 8L), moved.map { it.id })
        assertTrue(moved.findActionNode(3)!!.children.isEmpty())
    }

    @Test
    fun movesRootIntoThirdLevel() {
        val moved = move(1, 3, null, 0)!!
        assertEquals(listOf(2L, 6L, 7L, 9L, 8L), moved.map { it.id })
        assertEquals(listOf(1L, 4L), moved.findActionNode(3)!!.children.map { it.id })
    }

    @Test
    fun movesOutOfElseBranch() {
        val moved = move(5, null, null, 1)!!
        assertEquals(listOf(1L, 5L, 2L, 6L, 7L, 9L, 8L), moved.map { it.id })
        assertTrue(moved.findActionNode(2)!!.branches[0].children.isEmpty())
    }

    @Test
    fun movesIntoElseBranch() {
        val moved = move(1, 2, 0, 0)!!
        assertEquals(listOf(1L, 5L), moved.findActionNode(2)!!.branches[0].children.map { it.id })
        assertEquals(listOf(2L, 6L, 7L, 9L, 8L), moved.map { it.id })
    }

    @Test
    fun movesIntoEmptyBlockAtZero() {
        val moved = move(4, 7, null, 0)!!
        assertEquals(listOf(4L), moved.findActionNode(7)!!.children.map { it.id })
    }

    @Test
    fun movesIntoTargetsEmptyElseBranch() {
        val moved = move(4, 2, 1, 0)!!
        assertEquals(listOf(4L), moved.findActionNode(2)!!.branches[1].children.map { it.id })
    }

    @Test
    fun movingIntoCollapsedBlockKeepsItsCollapsedFlag() {
        val moved = move(4, 9, null, 0)!!
        val target = moved.findActionNode(9)!!
        assertTrue(target.collapsed)
        assertEquals(listOf(4L, 10L), target.children.map { it.id })
    }

    @Test
    fun rejectsSelfDescendantNonContainerAndBadIndices() {
        assertNull("自己", move(2, 2, null, 0))
        assertNull("自己的后代", move(2, 3, null, 0))
        assertNull("等待直到不收步骤", move(4, 8, null, 0))
        assertNull("不存在的分支", move(4, 2, 9, 0))
        assertNull("根层不能带分支", move(4, null, 0, 0))
        assertNull("索引越界", move(4, null, null, 99))
        assertNull("负索引", move(4, null, null, -1))
        assertNull("不存在的来源", move(999, null, null, 0))
        assertNull("不能移进不存在的父块", move(4, 999, null, 0))
    }

    @Test
    fun moveTargetsListsAncestorsEmptyAndCollapsedBlocksButNotSourceSubtree() {
        val sourceBlockTargets = moveTargets(tree(), sourceId = 2)
        assertTrue("根层", sourceBlockTargets.any { it.parentId == null })
        assertTrue("空块", sourceBlockTargets.any { it.parentId == 7L && it.detail.contains("空块") })
        assertTrue("折叠块", sourceBlockTargets.any { it.parentId == 9L && it.detail.contains("已折叠") })
        assertTrue("来源自身不可作为目标", sourceBlockTargets.none { it.parentId == 2L })
        assertTrue("来源后代不可作为目标", sourceBlockTargets.none { it.parentId == 3L })

        val rootStepTargets = moveTargets(tree(), sourceId = 1)
        assertTrue("否则分支", rootStepTargets.any { it.parentId == 2L && it.branch == 1 })
        assertTrue("深层块", rootStepTargets.any { it.parentId == 3L })
    }

    @Test
    fun stateMoveWritesOnceAndUndoRestoresTheWholeTree() {
        val state = AppState()
        val cmd = state.create(auto = true)
        val ifOption = CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "block.if" }
        val loopOption = CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "block.loop" }
        val stepOption = CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.toast" }
        state.addItem(cmd.id, CommandSection.ACTIONS, ifOption)
        state.addItem(cmd.id, CommandSection.ACTIONS, stepOption)
        val ifId = requireNotNull(state.find(cmd.id)).actions.first().id
        val rootStepId = requireNotNull(state.find(cmd.id)).actions.last().id
        state.addChildItem(cmd.id, ifId, null, loopOption)
        val loopId = requireNotNull(state.find(cmd.id)).actions.first().children.single().id
        state.addChildItem(cmd.id, loopId, null, stepOption)
        val deepId = requireNotNull(state.find(cmd.id)).actions.findActionNode(loopId)!!.children.single().id
        val before = requireNotNull(state.find(cmd.id)).actions

        assertTrue("跨层移动应成功", state.moveActionTo(cmd.id, deepId, null, null, 0))
        assertNotNull("移动后应显示撤销条", state.undo)
        assertEquals("已移动「${state.undo?.label}」", state.undo?.message)
        assertEquals(deepId, requireNotNull(state.find(cmd.id)).actions.first().id)

        state.undoDelete()
        assertEquals("撤销应恢复整棵树", before, requireNotNull(state.find(cmd.id)).actions)
    }
}
