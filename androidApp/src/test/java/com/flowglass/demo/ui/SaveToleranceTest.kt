package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
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
    fun renamingACalledCommandRewritesTheCaller() {
        val state = state(
            cmd(1, "调用方", listOf(call(10, "被调用方"))),
            cmd(2, "被调用方", emptyList()),
        )
        state.rename(2, "改名后的指令")

        val text = AppStateJson.encode(state.snapshot())

        val decoded = AppStateJson.decode(text)
        assertEquals("改名后的指令", decoded.oneTaps.map { it.title }.first { it != "调用方" })
        assertEquals("改名必须跟随调用边重写目标名", "改名后的指令|等它执行完再继续",
            decoded.oneTaps.first { it.title == "调用方" }.actions.single().parameter)
        assertTrue("调用边已修复，不应再报目标缺失",
            state.validationIssues(state.oneTaps.first { it.title == "调用方" }).none { it.contains("找不到目标指令") })
    }

    @Test
    fun aDanglingCallStillSavesAndIsReported() {
        val state = state(cmd(1, "调用方", listOf(call(10, "从未存在"))))

        val text = AppStateJson.encode(state.snapshot())

        assertEquals(1, AppStateJson.decode(text).oneTaps.size)
        assertTrue("编辑器仍须报告目标缺失",
            state.validationIssues(state.oneTaps.first()).any { it.contains("找不到目标指令") })
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

    @Test
    fun anUnparseableLegacyParameterSurvivesTheNextSave() {
        val state = state(cmd(1, "坏参数", listOf(CommandItem(10, "延时", "5 秒", Ic.Bolt, "action.delay"))))
        val legacy = JSONObject(AppStateJson.encode(state.snapshot()))
        val rule = legacy.getJSONArray("rules").getJSONObject(0)
        val action = rule.getJSONArray("actions").getJSONObject(0)
        action.getJSONObject("params").put("raw", "abc 秒")

        val decoded = AppStateJson.decode(legacy.toString())
        val text = AppStateJson.encode(decoded)

        val saved = JSONObject(text).getJSONArray("rules").getJSONObject(0)
            .getJSONArray("actions").getJSONObject(0).getJSONObject("params")
        assertEquals("读不动的旧参数必须原样保留，不能改成默认值", "abc 秒", saved.getString("raw"))
    }

    @Test
    fun deletingACalledCommandIsRefusedInsteadOfLeavingADanglingName() {
        val state = state(
            cmd(1, "调用方", listOf(call(10, "被调用方"))),
            cmd(2, "被调用方", emptyList()),
        )

        state.delete(2)

        assertEquals(2, state.oneTaps.size)
        assertTrue(state.editorNotice.orEmpty().contains("正在调用"))
    }

    @Test
    fun renamingFromTheEditorAlsoRewritesTheCaller() {
        val state = state(
            cmd(1, "调用方", listOf(call(10, "被调用方"))),
            cmd(2, "被调用方", emptyList()),
        )
        state.openEditor(2)
        state.setTitle(2, "编辑器里改的名")
        state.closeEditor()

        assertEquals("编辑器标题栏也必须跟随调用边", "编辑器里改的名|等它执行完再继续",
            state.oneTaps.first { it.title == "调用方" }.actions.single().parameter)
    }

    @Test
    fun anEditorTitleChangeRebindsBeforeTheUserJumpsToAnotherCommand() {
        val state = state(
            cmd(1, "调用方", listOf(call(10, "被调用方"))),
            cmd(2, "被调用方", emptyList()),
        )
        state.openEditor(2)
        state.setTitle(2, "即时改名")
        state.openEditor(1)

        assertEquals("即时改名|等它执行完再继续",
            state.oneTaps.first { it.title == "调用方" }.actions.single().parameter)
    }

    @Test
    fun aDuplicateEditorTitleIsRejectedBeforeAutosaveCanPersistIt() {
        val state = state(
            cmd(1, "调用方", listOf(call(10, "被调用方"))),
            cmd(2, "被调用方", emptyList()),
        )
        state.openEditor(2)
        state.setTitle(2, "调用方")

        assertEquals("被调用方", state.find(2)?.title)
        assertEquals("被调用方|等它执行完再继续",
            state.oneTaps.first { it.title == "调用方" }.actions.single().parameter)
        assertTrue(state.editorNotice.orEmpty().contains("同名"))
    }
}
