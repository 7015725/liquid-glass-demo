package com.flowglass.demo.ui.editor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flowglass.engine.*
import org.junit.Assert.*
import org.junit.Rule as JunitRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComponentWorkflowTest {
    @get:JunitRule val compose = createComposeRule()

    @Test fun orderCopyDeleteUseActualActionControls() {
        val first = ComponentRegistry.newComponent("action.stop")
        val second = ComponentRegistry.newComponent("action.delay")
        var saved: Rule? = null
        compose.setContent { RuleEditorScreen(Rule(name = "动作排序", type = "manual", actions = listOf(first, second)), emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").assertIsDisplayed().performClick()
        compose.onNodeWithTag("up-${second.id}").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("copy-${second.id}").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("动作 · 3").assertExists()
        compose.onNodeWithTag("delete-${first.id}").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved).actions
            assertEquals(2, result.size)
            assertEquals(second.id, result.first().id)
            assertNotEquals(second.id, result.last().id)
            assertEquals(second.params, result.last().params)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowScreenLongListStillAllowsCopyAndDeleteByTouch() {
        val actions = List(10) { ComponentRegistry.newComponent("action.delay") }
        var saved: Rule? = null
        compose.setContent { RuleEditorScreen(Rule(name = "窄屏长列表", type = "manual", actions = actions), emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").assertIsDisplayed().performClick()
        compose.onNodeWithTag("copy-${actions.last().id}").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("动作 · 11").assertExists()
        compose.onNodeWithTag("delete-${actions.first().id}").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("rule-save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved).actions
            assertEquals(10, result.size)
            assertFalse(result.any { it.id == actions.first().id })
            assertNotEquals(actions.last().id, result.last().id)
            assertEquals(actions.last().params, result.last().params)
        }
    }

    @Test fun invalidActionTimeoutStaysInForm() {
        val action = ComponentRegistry.newComponent("action.stop")
        compose.setContent { RuleEditorScreen(Rule(name = "超时校验", type = "manual", actions = listOf(action)), emptyList(), {}, {}) }
        compose.onNodeWithTag("tab-actions").assertIsDisplayed().performClick()
        compose.onNodeWithTag("edit-${action.id}").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("component-timeout").performScrollTo().performTextReplacement("1.5")
        compose.onNodeWithTag("component-done").performClick()
        compose.onNodeWithTag("component-errors").assertTextContains("timeoutSeconds", substring = true)
        compose.onNodeWithTag("component-timeout").assertTextContains("1.5")
    }

    @Test fun nestedIfThenActionIsAuthoredAndSaved() {
        val action = ComponentRegistry.newComponent("action.if")
        var saved: Rule? = null
        compose.setContent { RuleEditorScreen(Rule(name = "条件分支", type = "manual", actions = listOf(action)), emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").assertIsDisplayed().performClick()
        compose.onNodeWithTag("edit-${action.id}").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("param-expression").performScrollTo().performTextReplacement("true")
        compose.onNodeWithTag("add-${action.id}-children").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("component-search").performTextReplacement("停止任务")
        compose.onNodeWithTag("pick-action.stop").performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodesWithTag("component-done").onLast().performClick()
        compose.onNodeWithTag("component-done").performClick()
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle { assertEquals("action.stop", saved?.actions?.single()?.children?.single()?.type) }
    }
}
