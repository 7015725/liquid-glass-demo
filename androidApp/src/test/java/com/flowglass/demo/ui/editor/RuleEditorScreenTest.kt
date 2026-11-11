package com.flowglass.demo.ui.editor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flowglass.engine.Rule
import org.junit.Rule as JunitRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleEditorScreenTest {
    @get:JunitRule val compose = createComposeRule()

    @Test fun nameInputEditsTheRealField() {
        compose.setContent { RuleEditorScreen(Rule(), emptyList(), {}, {}) }
        compose.onNodeWithTag("rule-name").performTextReplacement("到家静音")
        compose.onNodeWithTag("rule-name").assertTextContains("到家静音")
    }

    @Test fun saveRejectsEmptyNameAndDoesNotClose() {
        var saves = 0
        var cancels = 0
        compose.setContent { RuleEditorScreen(Rule(), emptyList(), { saves++ }, { cancels++ }) }
        compose.onNodeWithTag("rule-save").performClick()
        compose.onNodeWithTag("validation-errors").assertTextContains("名称不能为空", substring = true)
        compose.runOnIdle { org.junit.Assert.assertEquals(0, saves); org.junit.Assert.assertEquals(0, cancels) }
    }

    @Test fun manualModeHidesTriggersAndConditions() {
        compose.setContent { RuleEditorScreen(Rule(), emptyList(), {}, {}) }
        compose.onNodeWithTag("type-manual").performClick()
        compose.onNodeWithTag("tab-triggers").assertDoesNotExist()
        compose.onNodeWithTag("tab-conditions").assertDoesNotExist()
        compose.onNodeWithTag("tab-actions").assertExists()
    }

    @Test fun addActionFromSearchThenSaveKeepsEditorOpen() {
        var saved: Rule? = null
        var cancels = 0
        compose.setContent { RuleEditorScreen(Rule(name = "手动静音", type = "manual"), emptyList(), { saved = it }, { cancels++ }) }
        compose.onNodeWithTag("tab-actions").assertIsDisplayed().performClick()
        compose.onNodeWithTag("add-actions").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("component-search").performTextReplacement("铃声")
        compose.onNodeWithTag("pick-action.set_ringer").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("component-done").performClick()
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("action.set_ringer", saved?.actions?.single()?.type)
            org.junit.Assert.assertEquals(0, cancels)
        }
        compose.onNodeWithTag("rule-save").assertExists()
    }

    @Test fun draftSurvivesRecreationAndDirtyCancelRequiresConfirmation() {
        var canceled = 0
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(Rule(id = "draft-stable"), emptyList(), {}, { canceled++ }) }
        compose.onNodeWithTag("rule-name").performTextReplacement("未完成草稿")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("rule-name").assertTextContains("未完成草稿")
        compose.onNodeWithTag("editor-back").performClick()
        compose.onNodeWithTag("discard-confirm").assertExists()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, canceled) }
        compose.onNodeWithTag("discard-confirm").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, canceled) }
    }

    @Test fun invalidDebounceIsRetainedAndBlocksSaving() {
        var saves = 0
        compose.setContent { RuleEditorScreen(Rule(name = "保存测试", type = "manual", actions = listOf(com.flowglass.engine.ComponentRegistry.newComponent("action.stop"))), emptyList(), { saves++ }, {}) }
        compose.onNodeWithTag("rule-debounce").performScrollTo().performTextReplacement("not-a-number")
        compose.onNodeWithTag("rule-save").performClick()
        compose.onNodeWithTag("validation-errors").assertTextContains("debounceMillis", substring = true)
        compose.onNodeWithTag("rule-debounce").assertTextContains("not-a-number")
        compose.runOnIdle { org.junit.Assert.assertEquals(0, saves) }
    }

    @Test fun treeBeyondEditorDepthCannotBeSaved() {
        val trigger = com.flowglass.engine.ComponentRegistry.newComponent("trigger.screen")
        var tree = com.flowglass.engine.MatchNode(ref = trigger.id)
        repeat(6) { tree = com.flowglass.engine.MatchNode(op = "not", children = listOf(tree)) }
        var saves = 0
        compose.setContent { RuleEditorScreen(Rule(name = "深度上限", triggers = listOf(trigger), triggerMatch = com.flowglass.engine.MatchSpec(mode = "custom", tree = tree), actions = listOf(com.flowglass.engine.ComponentRegistry.newComponent("action.stop"))), emptyList(), { saves++ }, {}) }
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, saves) }
        compose.onNodeWithTag("validation-errors").assertTextContains("triggerMatch", substring = true)
    }

    @Test fun customTreeCreatesNotOfStableComponentReference() {
        val trigger = com.flowglass.engine.ComponentRegistry.newComponent("trigger.screen")
        var saved: Rule? = null
        compose.setContent { RuleEditorScreen(Rule(name = "屏幕规则", triggers = listOf(trigger), actions = listOf(com.flowglass.engine.ComponentRegistry.newComponent("action.stop"))), emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-triggers").performClick()
        compose.onNodeWithTag("triggerMatch-custom").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("triggerMatch-tree").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("triggerMatch-tree").assertIsSelected()
        compose.onNodeWithTag("tree-root-not").assertExists()
        compose.onNodeWithTag("tree-root-not").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("not", saved?.triggerMatch?.tree?.op)
            org.junit.Assert.assertEquals(trigger.id, saved?.triggerMatch?.tree?.children?.single()?.ref)
        }
    }
}
