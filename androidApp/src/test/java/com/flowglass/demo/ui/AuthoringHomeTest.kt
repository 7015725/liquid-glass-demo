package com.flowglass.demo.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real rendered entry path: the authoring build must not report the demo's fake runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthoringHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstLaunchClearlyIdentifiesAuthoringRatherThanClaimingExecution() {
        compose.mainClock.autoAdvance = false
        compose.setContent { FlowGlassApp() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("指令编写器").assertIsDisplayed()
        compose.onNodeWithText("运行中").assertDoesNotExist()
        compose.onNodeWithText("今日 86 次").assertDoesNotExist()
    }
}
