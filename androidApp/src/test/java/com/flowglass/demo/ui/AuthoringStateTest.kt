package com.flowglass.demo.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.flowglass.demo.data.RuleRepository
import com.flowglass.engine.ComponentRegistry
import com.flowglass.engine.Rule as AutomationRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthoringStateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    @Before fun reset() { app.deleteDatabase("rule-authoring.db") }
    @After fun cleanup() { app.deleteDatabase("rule-authoring.db") }
    private fun settle(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out awaiting real database/state transition", predicate())
    }
    @Test fun createSaveReopenRenameDuplicateDeleteAndUndoUseDisk() {
        val state = AppState(app)
        settle { state.loaded }
        assertTrue(state.rules.isEmpty())
        state.create(false)
        assertTrue(state.rules.isEmpty())
        val draft = state.editor!!.copy(name = "一键测试", actions = listOf(ComponentRegistry.newComponent("action.delay")))
        state.saveEditor(draft)
        settle { !state.busy && state.rules.size == 1 }
        assertNull(state.editor)
        state.edit(draft.id)
        assertEquals(draft, state.editor)
        state.editor = null
        state.rename(draft.id, "修改名称")
        settle { !state.busy && state.rules.single().name == "修改名称" }
        state.duplicate(draft.id)
        settle { !state.busy && state.rules.size == 2 }
        assertEquals(2, state.rules.map { it.id }.distinct().size)
        state.delete(draft.id)
        settle { !state.busy && state.rules.size == 1 }
        state.undoDelete()
        settle { !state.busy && state.rules.size == 2 }
        runBlocking {
            val repository = RuleRepository(app)
            repository.refresh()
            assertEquals(2, repository.rules.value.size)
            assertTrue(repository.rules.value.all { !it.enabled })
            repository.close()
        }
    }
    @Test fun scriptImportCannotCommitWithoutExplicitConfirmation() {
        val state = AppState(app)
        settle { state.loaded }
        val script = ComponentRegistry.newComponent("action.shell").copy(params = mapOf("cmd" to kotlinx.serialization.json.JsonPrimitive("printf hello")))
        val rule = AutomationRule(name = "含脚本", type = "manual", actions = listOf(script), enabled = true)
        state.previewImport(com.flowglass.engine.RuleCodec.exportBundle(listOf(rule)))
        state.commitImport()
        assertFalse(state.busy)
        assertTrue(state.rules.isEmpty())
        assertNotNull(state.importPreview)
        state.importScriptConfirmed = true
        state.commitImport()
        settle { !state.busy && state.rules.size == 1 }
        assertFalse(state.rules.single().enabled)
        assertNotEquals(rule.id, state.rules.single().id)
    }
}
