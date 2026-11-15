package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionDeleteUndoTest {
    @Test
    fun deletingABlockWithChildrenOffersTheExistingUndoBar() {
        val state = AppState()
        val cmd = state.create(auto = true)
        val blockOption = CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "block.if" }
        val childOption = CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.toast" }
        state.addItem(cmd.id, CommandSection.ACTIONS, blockOption)
        val block = requireNotNull(state.find(cmd.id)).actions.single()
        state.addChildItem(cmd.id, block.id, null, childOption)
        val original = requireNotNull(state.find(cmd.id)).actions.single()
        assertEquals("块内子步骤应保留", 1, original.children.size)

        state.removeActionItemWithUndo(cmd.id, block.id)
        assertTrue("块应已删除", requireNotNull(state.find(cmd.id)).actions.isEmpty())
        assertEquals("撤销提示条应显示块名", block.name, state.undo?.label)

        state.undoDelete()
        val restored = requireNotNull(state.find(cmd.id)).actions.single()
        assertEquals("恢复块 id", block.id, restored.id)
        assertEquals("恢复块内子步骤", 1, restored.children.size)
    }
}
