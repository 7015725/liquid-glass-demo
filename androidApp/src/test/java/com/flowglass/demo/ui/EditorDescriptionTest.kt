package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Generated descriptions must use plain summaries instead of the stored "mode|times" text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorDescriptionTest {
    private fun loopOption() =
        CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "block.loop" }

    @Test
    fun generatedDescriptionUsesPlainSummaryAndFollowsTheCount() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, loopOption())
        val block = requireNotNull(state.find(cmd.id)).actions.single()

        val three = requireNotNull(state.find(cmd.id)).desc
        assertTrue("描述应显示循环次数：$three", three.contains("重复 3 次"))
        assertFalse("描述不能露出竖线参数：$three", '|' in three)

        state.setItemParameter(cmd.id, CommandSection.ACTIONS, block.id, "按次数|5")
        val five = requireNotNull(state.find(cmd.id)).desc
        assertTrue("次数改动后描述应更新：$five", five.contains("重复 5 次"))
        assertFalse(five.contains("|"))
    }

    @Test
    fun loadingRefreshesOnlyGeneratedDescriptions() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, loopOption())
        val raw = "循环：按次数|3"
        // Simulate a file written before the summary fix: generated text, never user-edited.
        state.autoCmds[0] = state.autoCmds[0].copy(desc = raw, descriptionEdited = false)
        val decoded = AppStateJson.decode(AppStateJson.encode(state.snapshot()))
        val loaded = AppState(decoded)
        val refreshed = requireNotNull(loaded.find(cmd.id)).desc
        assertFalse("加载后必须重算旧描述：$refreshed", '|' in refreshed)
        assertTrue(refreshed.contains("重复 3 次"))

        state.autoCmds[0] = state.autoCmds[0].copy(desc = "我自己的说明", descriptionEdited = true)
        val edited = AppState(AppStateJson.decode(AppStateJson.encode(state.snapshot())))
        assertEquals("手改描述不能被覆盖", "我自己的说明", requireNotNull(edited.find(cmd.id)).desc)
    }
}
