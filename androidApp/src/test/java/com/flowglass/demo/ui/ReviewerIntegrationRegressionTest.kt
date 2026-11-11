package com.flowglass.demo.ui

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.flowglass.engine.ComponentRegistry
import com.flowglass.engine.Rule as AutomationRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewerIntegrationRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val unavailable = "编写版未接入 Hook 执行，暂不支持激活。编写、保存和导出指令不需要 LSPosed。"

    @Before fun reset() {
        app.deleteDatabase("rule-authoring.db")
        compose.mainClock.autoAdvance = false
    }

    private fun settle(predicate: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            predicate()
        }
        compose.mainClock.advanceTimeBy(700)
    }

    private fun settings(page: Int): AppState {
        val state = AppState(app)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) { SettingsHost(state, page) }
        }
        settle { state.loaded }
        return state
    }

    @Test fun settingsDoesNotOfferAuthoringLsposedActivation() {
        val state = settings(SettingsPage.Main)
        compose.onAllNodes(hasText("LSPosed", substring = true) and hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText(unavailable).assertExists()
        compose.onNodeWithText("未激活").assertDoesNotExist()
        compose.onNodeWithText("LSPosed 激活").assertDoesNotExist()
        compose.onNodeWithText("Root 激活").assertHasClickAction()
        compose.onNodeWithText("App 保活").assertHasClickAction()
        compose.onNodeWithText("权限声明管理").assertHasClickAction()
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        assertEquals("0.4.0-authoring", version)
        compose.onNodeWithText(version).assertExists()
        assertEquals(SettingsPage.Main, state.settingsPage)
    }

    @Test fun directlyOpenedLsposedPageExplainsUnavailableWithoutActivationSteps() {
        settings(SettingsPage.LSPosed)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText(unavailable).assertIsDisplayed()
        compose.onNodeWithText("未激活").assertDoesNotExist()
        compose.onNodeWithText("激活步骤").assertDoesNotExist()
        compose.onNodeWithText("打开 LSPosed 管理器").assertDoesNotExist()
        assertEquals("LSPosed 说明", SettingsPage.title(SettingsPage.LSPosed))
    }

    private inner class DocumentRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val launches = mutableListOf<Pair<Int, Intent>>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            assertTrue(contract is ActivityResultContracts.CreateDocument)
            launches += requestCode to contract.createIntent(app, input)
        }
    }

    private inner class ExportSession {
        var state by mutableStateOf(AppState(app))
        var registry by mutableStateOf(DocumentRegistry())
        val restoration = StateRestorationTester(compose)
    }

    private fun startExport(): ExportSession {
        val session = ExportSession()
        session.restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides session.registry) {
                RuleFileTransfers(session.state)
                AuthoringFeedback(session.state)
            }
        }
        settle { session.state.loaded }
        compose.runOnIdle {
            session.state.saveEditor(AutomationRule(name = "导出快照", type = "manual",
                actions = listOf(ComponentRegistry.newComponent("action.stop"))))
        }
        settle { !session.state.busy && session.state.rules.size == 1 }
        compose.runOnIdle { session.state.message = null; session.state.requestExport() }
        settle { session.registry.launches.size == 1 }
        val intent = session.registry.launches.single().second
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals("流光指令.json", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertNotNull(session.state.exportPayload)
        return session
    }

    private fun recreateWhilePicking(session: ExportSession): Int {
        val requestCode = session.registry.launches.single().first
        val oldState = session.state
        compose.runOnIdle {
            val saved = Bundle()
            session.registry.onSaveInstanceState(saved)
            session.registry = DocumentRegistry().apply { onRestoreInstanceState(saved) }
            session.state = AppState(app)
        }
        // Restore the rememberSaveable launcher key, but not the lost ViewModel payload.
        session.restoration.emulateSavedInstanceStateRestore()
        settle { session.state.loaded }
        assertNotSame(oldState, session.state)
        assertEquals(1, session.state.rules.size)
        assertNull(session.state.exportPayload)
        assertNull(session.state.message)
        assertTrue(session.registry.launches.isEmpty())
        return requestCode
    }

    @Test fun restoredCreateDocumentResultReportsLostSnapshotWithoutWritingFreshContent() {
        val session = startExport()
        val requestCode = recreateWhilePicking(session)
        val document = File(app.cacheDir, "lost-snapshot.json").apply { writeText("") }
        compose.runOnIdle {
            assertTrue(session.registry.dispatchResult(requestCode, Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(document))))
        }
        val failure = "导出失败：导出快照已丢失，请重新导出。所选文件可能为空。"
        assertEquals(failure, session.state.message)
        settle { compose.onNodeWithText(failure).isDisplayed() }
        compose.onNodeWithText(failure).assertIsDisplayed()
        assertEquals("", document.readText())
        assertNull(session.state.exportPayload)
        assertTrue(session.registry.launches.isEmpty())
    }

    @Test fun cancelledCreateDocumentAfterRecreationDoesNotReportFailure() {
        val session = startExport()
        val requestCode = recreateWhilePicking(session)
        compose.runOnIdle {
            assertTrue(session.registry.dispatchResult(requestCode, Activity.RESULT_CANCELED, null))
        }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("feedback-dismiss").assertDoesNotExist()
        assertNull(session.state.message)
        assertNull(session.state.exportPayload)
    }

    @Test fun createDocumentWithSnapshotStillWritesAndVerifiesOriginalPayload() {
        val session = startExport()
        val requestCode = session.registry.launches.single().first
        val payload = session.state.exportPayload!!
        val document = File(app.cacheDir, "snapshot.json").apply { writeText("") }
        compose.runOnIdle {
            assertTrue(session.registry.dispatchResult(requestCode, Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(document))))
        }
        settle { session.state.message != null }
        compose.onNodeWithText("JSON 已导出并回读核验（含全局变量值）").assertIsDisplayed()
        assertEquals(payload, document.readText())
        assertNull(session.state.exportPayload)
    }
}
