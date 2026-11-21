package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One broken call link must not freeze persistence for every other edit. Loading stays tolerant,
 * the editor reports the dangling call, and the engine refuses to run past the depth limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SaveToleranceTest {
    private fun call(id: Long, target: String) =
        CommandItem(id, "执行另一条指令", "$target|等它执行完再继续", Ic.Bolt, "action.run_rule")

    private fun cmd(id: Long, title: String, actions: List<CommandItem>) =
        Cmd(id = id, auto = false, ic = Ic.Bolt, title = title, desc = "", actions = actions)

    private fun state(vararg cmds: Cmd): AppState = AppState().apply {
        oneTaps.clear(); oneTaps.addAll(cmds)
    }

    @Test
    fun danglingCallDoesNotBlockSavingAndStaysReported() {
        val state = state(
            cmd(1, "调用方", listOf(call(10, "被调用方"))),
            cmd(2, "被调用方", emptyList()),
        )
        state.rename(2, "改名后的指令")

        val text = AppStateJson.encode(state.snapshot())

        val decoded = AppStateJson.decode(text)
        assertEquals("改名后的指令", decoded.oneTaps.map { it.title }.first { it != "调用方" })
        assertEquals("改名后仍保留原调用参数，由编辑器提示修复", "被调用方|等它执行完再继续",
            decoded.oneTaps.first { it.title == "调用方" }.actions.single().parameter)
        assertTrue("编辑器仍须报告目标缺失",
            state.validationIssues(state.oneTaps.first { it.title == "调用方" }).any { it.contains("找不到目标指令") })
    }

    @Test
    fun callCycleDoesNotBlockSaving() {
        val state = state(
            cmd(1, "甲", listOf(call(10, "乙"))),
            cmd(2, "乙", listOf(call(20, "甲"))),
        )

        val text = AppStateJson.encode(state.snapshot())

        assertTrue("成环仍须在编辑器提示", state.validationIssues(state.oneTaps.first()).any { it.contains("成环") })
        assertTrue(text.contains("\"甲\"") && text.contains("\"乙\""))
    }
}
