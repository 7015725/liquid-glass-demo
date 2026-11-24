package com.flowglass.demo.ui

import com.flowglass.engine.OutputBinding
import com.flowglass.engine.Rule
import com.flowglass.engine.RulePresentation
import com.flowglass.engine.RuleUiMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renaming an output must follow `{旧名}` in its own command, and only there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutputRenameTest {
    private fun source() = CommandItem(
        id = 10,
        name = "执行 JavaScript",
        parameter = "1;",
        ic = Ic.Bolt,
        typeId = "action.script",
        outputs = listOf(OutputBinding("script.result", "结果", true)),
    )

    private fun consumer(parameter: String) = CommandItem(
        id = 11,
        name = "屏幕提示",
        parameter = parameter,
        ic = Ic.Bolt,
        typeId = "action.toast",
    )

    private fun state(vararg cmds: Cmd): AppState {
        val s = AppState()
        s.autoCmds.addAll(cmds)
        s.engineRules = cmds.map { Rule(id = "rule-${it.id}", name = it.title) }
        s.engineUi = RuleUiMetadata(
            presentation = cmds.associate { "rule-${it.id}" to RulePresentation(legacyId = it.id) },
        )
        return s
    }

    private fun cmd(id: Long, parameter: String) = Cmd(
        id = id,
        auto = true,
        ic = Ic.Bolt,
        title = "指令$id",
        desc = "",
        actions = listOf(source(), consumer(parameter)),
        enabled = false,
    )

    @Test
    fun outputRenameRewritesBareReferencesInItsCommandOnly() {
        val s = state(cmd(1, "{结果} + {{结果}} + {global.结果}"), cmd(2, "{结果}"))

        s.updateItemOutputs(
            cmdId = 1L,
            section = CommandSection.ACTIONS,
            itemId = 10L,
            outputs = listOf(OutputBinding("script.result", "新结果", true)),
        )

        assertEquals(
            "{新结果} + {{结果}} + {global.结果}",
            s.autoCmds.first { it.id == 1L }.actions.last().parameter,
        )
        assertEquals(
            "另一条指令的同名引用不得被改写",
            "{结果}",
            s.autoCmds.first { it.id == 2L }.actions.last().parameter,
        )
        assertEquals(
            listOf(OutputBinding("script.result", "新结果", true)),
            s.autoCmds.first { it.id == 1L }.actions.first().outputs,
        )
    }

    @Test
    fun keepingTheOldNameAsAnotherBindingDoesNotRewrite() {
        val s = state(cmd(1, "{结果}"))

        s.updateItemOutputs(
            cmdId = 1L,
            section = CommandSection.ACTIONS,
            itemId = 10L,
            outputs = listOf(
                OutputBinding("script.result", "新结果", true),
                OutputBinding("script.extra", "结果", true),
            ),
        )

        assertEquals("{结果}", s.autoCmds.first { it.id == 1L }.actions.last().parameter)
    }

    @Test
    fun discardingTheDraftDoesNotTouchReferences() {
        val s = state(cmd(1, "{结果}"))
        val original = s.autoCmds.first().actions.first()
        s.componentDraft = ComponentDraft(1L, CommandSection.ACTIONS, original, original)

        s.updateComponentOutputs(listOf(OutputBinding("script.result", "新结果", true)))
        s.cancelComponentDraft()

        assertEquals("{结果}", s.autoCmds.first().actions.last().parameter)
    }

    @Test
    fun savingTheDraftMovesReferencesOnce() {
        val s = state(cmd(1, "{结果}"))
        val original = s.autoCmds.first().actions.first()
        s.componentDraft = ComponentDraft(1L, CommandSection.ACTIONS, original, original)

        s.updateComponentOutputs(listOf(OutputBinding("script.result", "新结果", true)))
        s.saveComponentDraft()

        assertEquals("{新结果}", s.autoCmds.first().actions.last().parameter)
        assertEquals(
            listOf(OutputBinding("script.result", "新结果", true)),
            s.autoCmds.first().actions.first().outputs,
        )
    }
}
