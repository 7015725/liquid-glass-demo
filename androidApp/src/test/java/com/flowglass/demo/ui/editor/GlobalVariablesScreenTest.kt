package com.flowglass.demo.ui.editor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flowglass.engine.Variable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlobalVariablesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun invalidIntegerStaysEditableAndCannotBeSaved() {
        var saves = 0
        compose.setContent { GlobalVariablesScreen(emptyList(), { saves++ }, {}, {}) }
        compose.onNodeWithTag("global-add").performClick()
        compose.onNodeWithTag("variable-name").performTextReplacement("counter")
        compose.onNodeWithTag("variable-type-int").performScrollTo().performClick()
        compose.onNodeWithTag("variable-initial").performScrollTo().performTextReplacement("1.2")
        compose.onNodeWithTag("variable-save").performClick()
        compose.onNodeWithTag("variable-errors").assertExists()
        compose.onNodeWithTag("variable-initial").assertTextContains("1.2")
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun globalFormWaitsForParentPersistenceConfirmation() {
        var saved: Variable? = null
        val globals = androidx.compose.runtime.mutableStateOf<List<Variable>>(emptyList())
        compose.setContent { GlobalVariablesScreen(globals.value, { saved = it }, {}, {}) }
        compose.onNodeWithTag("global-add").performClick()
        compose.onNodeWithTag("variable-name").performTextReplacement("mode")
        compose.onNodeWithTag("variable-initial").performTextReplacement("home")
        compose.onNodeWithTag("variable-save").performClick()
        compose.onNodeWithTag("variable-save").assertExists()
        compose.runOnIdle { globals.value = listOf(requireNotNull(saved)) }
        compose.onNodeWithTag("variable-save").assertDoesNotExist()
        compose.onNodeWithTag("global-edit-mode").assertExists()
    }

    @Test fun longIntegerIsSavedWithoutDoubleRounding() {
        var saved: Variable? = null
        compose.setContent { GlobalVariablesScreen(emptyList(), { saved = it }, {}, {}) }
        compose.onNodeWithTag("global-add").performClick()
        compose.onNodeWithTag("variable-name").performTextReplacement("counter")
        compose.onNodeWithTag("variable-type-int").performScrollTo().performClick()
        compose.onNodeWithTag("variable-initial").performScrollTo().performTextReplacement("9223372036854775807")
        compose.onNodeWithTag("variable-save").performClick()
        compose.runOnIdle { assertEquals("9223372036854775807", saved?.initial.toString()) }
    }
}
