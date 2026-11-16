package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parameter page is a draft boundary: only [AppState.saveComponentDraft] writes a command. */
class ComponentDraftTest {
    private fun stateWithCommand(): Pair<AppState, Long> {
        val state = AppState()
        return state to state.create(auto = true).id
    }

    private fun actionOption(typeId: String = "action.set_brightness"): CommandOption =
        CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == typeId }

    @Test
    fun newDraftDoesNotInsertUntilSaved() {
        val (state, cmdId) = stateWithCommand()
        val option = actionOption()
        state.startComponentDraft(cmdId, CommandSection.ACTIONS, option)

        assertEquals("打开参数页时不能写入指令", 0, requireNotNull(state.find(cmdId)).actions.size)

        val draft = requireNotNull(state.componentDraft)
        state.updateComponentDraft(draft.item.copy(parameter = "42%"))
        assertTrue(state.saveComponentDraft())

        val saved = requireNotNull(state.find(cmdId)).actions.single()
        assertEquals(option.name, saved.name)
        assertEquals("42%", saved.parameter)
        assertNull(state.componentDraft)
    }

    @Test
    fun cancelNewDraftLeavesCommandUntouched() {
        val (state, cmdId) = stateWithCommand()
        state.startComponentDraft(cmdId, CommandSection.CONDITIONS, CommandCatalog.options(CommandSection.CONDITIONS)
            .single { it.typeId == "condition.charging" })

        assertEquals(0, requireNotNull(state.find(cmdId)).conditions.size)
        state.cancelComponentDraft()
        assertEquals(0, requireNotNull(state.find(cmdId)).conditions.size)
        assertNull(state.componentDraft)
    }

    @Test
    fun existingDraftDoesNotWriteUntilSaved() {
        val (state, cmdId) = stateWithCommand()
        val option = actionOption()
        state.addItem(cmdId, CommandSection.ACTIONS, option)
        val original = requireNotNull(state.find(cmdId)).actions.single()

        assertTrue(state.openComponentEditor(cmdId, CommandSection.ACTIONS, original))
        state.updateComponentDraft(original.copy(parameter = "77%"))
        assertEquals("打开/编辑参数页时原指令必须不变", original.parameter,
            requireNotNull(state.find(cmdId)).actions.single().parameter)

        state.saveComponentDraft()
        assertEquals("77%", requireNotNull(state.find(cmdId)).actions.single().parameter)
    }

    @Test
    fun parameterlessExistingItemShowsNoticeInsteadOfEmptyPage() {
        val (state, cmdId) = stateWithCommand()
        val option = CommandCatalog.options(CommandSection.TRIGGERS).single { it.typeId == "trigger.screen_on" }
        state.addItem(cmdId, CommandSection.TRIGGERS, option)
        val item = requireNotNull(state.find(cmdId)).triggers.single()

        assertFalse(state.openComponentEditor(cmdId, CommandSection.TRIGGERS, item))
        assertNull(state.componentDraft)
        assertEquals("该组件没有可编辑参数", state.editorNotice)
    }
}
