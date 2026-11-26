package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Clearing the editor title must stay a draft edit, not an immediate validation error. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorTitleClearTest {
    private fun state() = AppState().apply {
        autoCmds.add(Cmd(id = 1, auto = true, ic = Ic.Bolt, title = "自动指令", desc = "", actions = emptyList()))
        oneTaps.add(Cmd(id = 2, auto = false, ic = Ic.Bolt, title = "一键指令", desc = "", actions = emptyList()))
    }

    @Test
    fun clearingAnEditorTitleDoesNotRaiseAnErrorForAutoOrOneTapCommands() {
        val state = state()

        state.openEditor(1)
        state.setTitle(1, "")
        assertEquals("自动指令", state.find(1)?.title)
        assertNull("自动指令编辑中清空标题不应立即报错", state.editorNotice)

        state.openEditor(2)
        state.setTitle(2, "")
        assertEquals("一键指令", state.find(2)?.title)
        assertNull("一键指令编辑中清空标题不应立即报错", state.editorNotice)
    }
}
