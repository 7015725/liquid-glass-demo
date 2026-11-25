package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Automatic summaries are cancelled: only descriptions the user typed are kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorDescriptionTest {
    private fun loopOption() =
        CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "block.loop" }

    @Test
    fun addingActionsDoesNotCreateSummary() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, loopOption())
        state.setItemParameter(cmd.id, CommandSection.ACTIONS,
            requireNotNull(state.find(cmd.id)).actions.single().id, "按次数|5")

        assertEquals("自动摘要不应生成", "", requireNotNull(state.find(cmd.id)).desc)
    }

    @Test
    fun newCommandsHaveNoSummary() {
        val state = AppState()
        val auto = state.create(auto = true)
        val oneTap = state.create(auto = false)

        assertEquals("", requireNotNull(state.find(auto.id)).desc)
        assertEquals("", requireNotNull(state.find(oneTap.id)).desc)
    }

    @Test
    fun loadingClearsGeneratedTextAndKeepsEditedText() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, loopOption())
        val raw = "循环：按次数|3"
        // Simulate a file written before summaries were cancelled: generated text, never edited.
        state.autoCmds[0] = state.autoCmds[0].copy(desc = raw, descriptionEdited = false)
        val decoded = AppStateJson.decode(AppStateJson.encode(state.snapshot()))
        val loaded = AppState(decoded)
        assertEquals("旧自动摘要应在加载时清空", "", requireNotNull(loaded.find(cmd.id)).desc)

        state.autoCmds[0] = state.autoCmds[0].copy(desc = "我自己的说明", descriptionEdited = true)
        val edited = AppState(AppStateJson.decode(AppStateJson.encode(state.snapshot())))
        assertEquals("手改描述不能被覆盖", "我自己的说明", requireNotNull(edited.find(cmd.id)).desc)
    }
}
