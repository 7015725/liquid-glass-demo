package com.flowglass.demo.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.flowglass.engine.ComponentRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthoringNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    @Before fun reset() { app.deleteDatabase("rule-authoring.db") }
    private fun settle(test: () -> Boolean) {
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            test()
        }
        compose.mainClock.advanceTimeBy(700)
    }
    @Test fun cardHasAccessibleClickAndLongClickActions() {
        var clicked = 0
        var longClicked = 0
        compose.setContent { PressCard(Modifier.testTag("card"), onClick = { clicked++ }, onLongPress = { longClicked++ }) { T("指令") } }
        compose.onNodeWithTag("card").assertHasClickAction().performClick()
        compose.onNodeWithTag("card").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { assertEquals(1, clicked); assertEquals(1, longClicked) }
    }
    @Test fun smallPressControlHasAccessibleClick() {
        var clicked = 0
        compose.setContent { Box(Modifier.size(48.dp).testTag("button").pressable { clicked++ }) { T("按钮") } }
        compose.onNodeWithTag("button").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }
    @Test fun editingAndSavingReturnsToTheSameList() {
        val state = AppState(app)
        compose.mainClock.autoAdvance = false
        compose.setContent { FlowGlassApp(state) }
        settle { state.loaded }
        val manualTab = hasText("一键指令") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(manualTab).assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onAllNodes(hasText("新建") and hasClickAction()).onFirst().assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("rule-name").performTextReplacement("UI保存闭环")
        compose.onNodeWithTag("tab-actions").performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("add-actions").performScrollTo().assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("component-search").performTextReplacement("停止任务")
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("pick-action.stop").performScrollTo().assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("component-done").performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("rule-save").performClick()
        try { settle { !state.busy && state.rules.size == 1 } } catch (failure: Throwable) {
            println("E2E SAVE: editor=${state.editor} busy=${state.busy} rules=${state.rules} message=${state.message}")
            println(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
        compose.onNodeWithTag("feedback-dismiss").performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("共 1 条 · 置顶 0").assertIsDisplayed()
        val id = state.rules.single().id
        compose.onNodeWithTag("edit-$id").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("rule-name").assertTextContains("UI保存闭环")
        compose.runOnIdle { assertEquals("action.stop", state.editor!!.actions.single().type) }
    }
    @Test fun settingsExposesRealLibraryInsteadOfDemoModes() {
        val state = AppState(app)
        compose.mainClock.autoAdvance = false
        compose.setContent { FlowGlassApp(state) }
        settle { state.loaded }
        val settingsTab = hasText("设置") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(settingsTab).performClick()
        compose.mainClock.advanceTimeBy(700)
        val installedVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        assertTrue(installedVersion.isNotEmpty())
        compose.onNodeWithText(installedVersion).assertExists()
        compose.onRoot().performTouchInput { swipeUp(startY = bottom - 50f, endY = centerY, durationMillis = 1000) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("全局变量").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("global-add").assertIsDisplayed()
        compose.onNodeWithText("运行模式").assertDoesNotExist()
    }
}
