package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * STO-12：恢复会整份替换规则与变量。旧世代在途任务不能把结果写进新文档，恢复也必须
 * 丢弃旧的撤销、历史面板和菜单状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreBarrierTest {
    private fun replacement(): PersistedAppState {
        val cmd = Cmd(
            id = 7, auto = false, ic = Ic.Bolt, title = "恢复后", desc = "",
            actions = listOf(CommandItem(8, "延迟", "1", Ic.Timer, "action.delay")),
        )
        return PersistedAppState(emptyList(), listOf(cmd), emptyList(), true, 1)
    }

    @Test
    fun restoreAdvancesTheDocumentEpoch() {
        val state = AppState()
        val before = state.documentEpoch
        state.replaceWith(replacement())
        assertTrue("恢复必须开启新世代", state.documentEpoch > before)
    }

    @Test
    fun restoreDropsUndoAndTransientUiState() {
        val state = AppState()
        state.undo = UndoInfo(state.create(auto = false), 0, label = "删除")
        state.historyOpen = true

        state.replaceWith(replacement())

        assertNull("旧撤销会指回被替换的文档", state.undo)
        assertFalse("恢复后不应保留历史面板", state.historyOpen)
        assertEquals("恢复后", state.oneTaps.single().title)
    }

    @Test
    fun restoreClearsExecutionBadges() {
        val state = AppState()
        state.executing[7] = 1
        state.executionMessages[7] = "运行中"

        state.replaceWith(replacement())

        assertTrue(state.executing.isEmpty())
        assertTrue(state.executionMessages.isEmpty())
    }
}
