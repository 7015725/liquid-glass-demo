package com.flowglass.demo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun hiddenSheetDoesNotCreateAModalWindow() {
        compose.setContent { GlassSheet(false, "隐藏", {}) { T("不应出现") } }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("不应出现").assertDoesNotExist()
    }

    @Test fun contentClickDoesNotDismissAndCloseButtonIsAccessible() {
        val visible = mutableStateOf(true)
        var selected = 0
        var dismissed = 0
        compose.setContent {
            GlassSheet(visible.value, "内容点击", { dismissed++; visible.value = false }) { backdrop ->
                T("静态说明")
                com.flowglass.demo.liquid.LiquidButton({ selected++ }, backdrop,
                    modifier = androidx.compose.ui.Modifier.testTag("inside-action"), isInteractive = false) { T("选项") }
            }
        }
        compose.onNodeWithTag("glass-sheet-handle").performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals("Handle taps must not reach the scrim", 0, dismissed) }
        compose.onNodeWithText("静态说明").performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals("Body taps must not reach the scrim", 0, dismissed) }
        compose.onNodeWithTag("inside-action").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, selected); org.junit.Assert.assertEquals(0, dismissed) }
        compose.onNodeWithTag("glass-sheet-close").assertContentDescriptionEquals("关闭面板")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, dismissed) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun systemBackClosesOnlyTheTopSheet() {
        val outer = mutableStateOf(true)
        val inner = mutableStateOf(true)
        compose.setContent {
            GlassSheet(outer.value, "外层", { outer.value = false }) {
                T("外层内容")
                GlassSheet(inner.value, "内层", { inner.value = false }) { T("内层内容") }
            }
        }
        compose.onNodeWithText("内层内容").assertIsDisplayed()
        pressBackOnTopDialog()
        compose.onNodeWithText("内层内容").assertDoesNotExist()
        compose.onNodeWithText("外层内容").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertTrue(outer.value); org.junit.Assert.assertFalse(inner.value) }
        pressBackOnTopDialog()
        compose.onNodeWithText("外层内容").assertDoesNotExist()
    }

    @Test fun callerMayRejectDismissalWithoutLosingContent() {
        var requests = 0
        compose.setContent { GlassSheet(true, "保留草稿", { requests++ }) { T("草稿仍在") } }
        compose.onNodeWithTag("glass-sheet-close").performClick()
        compose.onNodeWithText("草稿仍在").assertIsDisplayed()
        compose.onNodeWithTag("glass-sheet").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { it() }
        compose.runOnIdle { org.junit.Assert.assertEquals(2, requests) }
        compose.onNodeWithText("草稿仍在").assertIsDisplayed()
    }

    @Test fun rawInputSurvivesRecreationAndClosingThenReopening() {
        val visible = mutableStateOf(true)
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            GlassSheet(visible.value, "原文恢复", { visible.value = false }) {
                var raw by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
                com.flowglass.demo.ui.editor.EditorField("原始输入", raw, { raw = it }, "sheet-input")
            }
        }
        compose.onNodeWithTag("sheet-input").performTextReplacement("not-a-number")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("sheet-input").assertTextContains("not-a-number")
        compose.onNodeWithTag("glass-sheet-close").performClick()
        compose.onNodeWithTag("sheet-input").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("sheet-input").assertTextContains("not-a-number")
        compose.onNodeWithTag("sheet-input").performTextReplacement("42")
        compose.onNodeWithTag("sheet-input").assertTextContains("42")
    }

    @Test fun longContentScrollsWithinInsetsAndKeepsCloseVisible() {
        compose.setContent {
            GlassSheet(true, "长内容", {},
                windowInsets = androidx.compose.foundation.layout.WindowInsets(left = 20, top = 40, right = 20, bottom = 240)) {
                repeat(30) { T("内容 $it", modifier = androidx.compose.ui.Modifier.testTag("row-$it")) }
            }
        }
        val viewport = compose.onNodeWithTag("glass-sheet-overlay").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("glass-sheet").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(sheet.left >= 20f && sheet.right <= viewport.right - 20f)
        org.junit.Assert.assertTrue(sheet.top >= 40f && sheet.bottom <= viewport.bottom - 240f)
        org.junit.Assert.assertTrue(sheet.height <= (viewport.height - 280f) * 0.85f + 1f)
        compose.onNodeWithTag("row-29").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("glass-sheet-close").assertIsDisplayed()
    }

    @Test fun sheetSlidesUpAndFinishesSlidingOutBeforeDisposal() {
        compose.mainClock.autoAdvance = false
        val visible = mutableStateOf(false)
        compose.setContent { GlassSheet(visible.value, "滑出动画", { visible.value = false }) { repeat(8) { T("面板行 $it") } } }
        compose.runOnIdle { visible.value = true }
        settleFrames(64)
        val enteringTop = compose.onNodeWithTag("glass-sheet").fetchSemanticsNode().boundsInRoot.top
        settleFrames(400)
        val restingTop = compose.onNodeWithTag("glass-sheet").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue("Sheet must move upward", enteringTop > restingTop)
        compose.onNodeWithTag("glass-sheet-close").performClick()
        settleFrames(64)
        compose.onNodeWithTag("glass-sheet").assertExists()
        settleFrames(400)
        compose.onNodeWithTag("glass-sheet").assertDoesNotExist()
    }

    private fun pressBackOnTopDialog() {
        compose.runOnIdle {
            val dialog = org.robolectric.shadows.ShadowDialog.getShownDialogs().last { it.isShowing }
            dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
    }

    private fun settleFrames(millis: Long) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    @Test fun sheetOpensAndScrimDismissesIt() {
        val visible = mutableStateOf(true)
        compose.setContent {
            GlassSheet(visible.value, "面板测试", { visible.value = false }) { T("面板内容") }
        }
        compose.onNodeWithText("面板内容").assertIsDisplayed()
        compose.onNodeWithTag("glass-sheet-scrim").performTouchInput { click(Offset(centerX, 4f)) }
        compose.onNodeWithText("面板内容").assertDoesNotExist()
    }
}
