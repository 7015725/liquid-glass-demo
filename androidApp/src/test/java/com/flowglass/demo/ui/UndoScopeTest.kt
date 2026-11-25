package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The undo bar reverses one delete or one move. It must not roll the whole command back to the
 * snapshot taken before that action, because unrelated edits made in the meantime would vanish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UndoScopeTest {
    private fun item(id: Long, name: String, type: String = "action.toast"): CommandItem =
        CommandItem(id, name, "", Ic.Bolt, type)

    private fun command(vararg actions: CommandItem): Cmd = Cmd(
        id = 1L, auto = true, ic = Ic.Bolt, title = "原标题", desc = "",
        actions = actions.toList(), enabled = false,
    )

    private fun state(cmd: Cmd): AppState = AppState().apply { autoCmds.add(cmd) }

    @Test
    fun undoOfADeletedStepKeepsEditsMadeAfterTheDelete() {
        val s = state(command(item(10, "提示"), item(11, "延时")))

        s.removeActionItemWithUndo(1L, 11L)
        s.rename(1L, "删除后新标题")
        s.undoDelete()

        val cmd = s.find(1L)!!
        assertEquals("撤销删除不得回退无关的标题修改", "删除后新标题", cmd.title)
        assertEquals(listOf(10L, 11L), cmd.actions.map { it.id })
    }

    @Test
    fun undoOfADeletedStepRestoresTheActionWithoutASummary() {
        // Untouched commands no longer generate a subtitle; undo must still restore the action
        // itself and leave the description empty.
        val s = state(command(item(10, "提示"), item(11, "延时")).copy(descriptionEdited = false))
        s.removeActionItemWithUndo(1L, 11L)
        assertEquals(1, s.find(1L)!!.actions.size)
        assertTrue("删除后不应生成摘要", s.find(1L)!!.desc.isEmpty())

        s.undoDelete()

        assertTrue("撤销后仍不应生成摘要", s.find(1L)!!.desc.isEmpty())
        assertEquals(listOf(10L, 11L), s.find(1L)!!.actions.map { it.id })
    }

    @Test
    fun undoOfAMoveKeepsEditsMadeAfterTheMove() {
        val container = CommandItem(20L, "如果", "", Ic.Bolt, "block.if", children = emptyList())
        val s = state(command(container, item(21, "提示")))

        assertTrue(s.moveActionTo(1L, 21L, 20L, null, 0))
        s.rename(1L, "移动后新标题")
        s.undoDelete()

        val cmd = s.find(1L)!!
        assertEquals("撤销移动不得回退无关的标题修改", "移动后新标题", cmd.title)
        assertEquals("步骤应回到原来的顶层位置", listOf(20L, 21L), cmd.actions.map { it.id })
        assertTrue("容器内不应再留有该步骤", cmd.actions.first { it.id == 20L }.children.isEmpty())
    }

    @Test
    fun undoOfADeletedNestedConditionKeepsEditsMadeAfterwards() {
        val condition = CommandItem(32L, "屏幕亮", "", Ic.Bolt, "condition.screen")
        val block = CommandItem(
            30L, "如果", "", Ic.Bolt, "block.if",
            conditions = listOf(condition), children = listOf(item(31, "提示")),
        )
        val s = state(command(block))

        s.removeConditionItemWithUndo(1L, 30L, null, 32L)
        s.rename(1L, "删条件后新标题")
        s.undoDelete()

        val cmd = s.find(1L)!!
        assertEquals("撤销删除条件不得回退无关的标题修改", "删条件后新标题", cmd.title)
        assertEquals("条件应回到原位置", listOf(32L), cmd.actions.first { it.id == 30L }.conditions.map { it.id })
    }
}
