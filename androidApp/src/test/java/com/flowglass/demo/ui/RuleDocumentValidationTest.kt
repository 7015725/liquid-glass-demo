package com.flowglass.demo.ui

import android.util.AtomicFile
import com.flowglass.engine.ComponentPresentation
import com.flowglass.engine.LegacyCommandMigration
import com.flowglass.engine.Rule as EngineRule
import com.flowglass.engine.RuleComponent
import com.flowglass.engine.RuleDocument
import com.flowglass.engine.RuleJson
import com.flowglass.engine.RulePresentation
import com.flowglass.engine.RuleUiMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleDocumentValidationTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "commands.json")
    private val ruleId = LegacyCommandMigration.uuidForLegacyId(1)

    @Test fun invalidV2ModeFailsSafeWithoutOverwritingOriginal() {
        for (mode in listOf(3, -1, Int.MAX_VALUE)) {
            assertRejected(document().copy(ui = document().ui.copy(mode = mode)), "mode=$mode")
        }
    }

    @Test fun duplicateItemDeclarationsAreRejected() {
        val base = document()
        val sidecar = base.ui.presentation.getValue(ruleId)
        assertRejected(base.copy(
            rules = listOf(base.rules.single().copy(actions = base.rules.single().actions + base.rules.single().actions)),
            ui = base.ui.copy(presentation = mapOf(ruleId to sidecar.copy(actions = sidecar.actions + sidecar.actions))),
        ), "duplicate item declarations")
    }

    @Test fun duplicateLogDeclarationsAreRejected() {
        assertRejected(document().copy(ui = document().ui.copy(logs = listOf(log(3), log(3)))), "duplicate log declarations")
    }

    @Test fun declarationIdsCannotCollideAcrossCommandItemAndLog() {
        for (id in listOf(1L, 2L)) {
            assertRejected(document().copy(ui = document().ui.copy(logs = listOf(log(id)))), "log collision $id")
        }
    }

    @Test fun outOfRangeCommandItemAndLogIdsAreRejected() {
        for (id in listOf(-1L, 0L, Long.MAX_VALUE)) {
            val base = document()
            val sidecar = base.ui.presentation.getValue(ruleId)
            assertRejected(base.copy(ui = base.ui.copy(presentation = mapOf(ruleId to sidecar.copy(legacyId = id)))), "command id=$id")
            assertRejected(base.copy(ui = base.ui.copy(presentation = mapOf(ruleId to sidecar.copy(actions = listOf(sidecar.actions.single().copy(id = id)))))), "item id=$id")
            assertRejected(base.copy(ui = base.ui.copy(logs = listOf(log(id)))), "log id=$id")
        }
    }

    @Test fun invalidStepReferencesAndNegativeDurationsAreRejected() {
        for (id in listOf(-1L, 0L, Long.MAX_VALUE)) {
            assertRejected(document().copy(ui = document().ui.copy(logs = listOf(log(3, id)))), "step reference=$id")
        }
        val badLog = log(3).let { it.copy(steps = listOf(it.steps.single().copy(durationMs = -1))) }
        assertRejected(document().copy(ui = document().ui.copy(logs = listOf(badLog))), "negative duration")
    }

    @Test fun repeatedStepReferencesIncludingDeletedItemsRemainLegal() {
        val first = log(3).let { it.copy(steps = it.steps + it.steps + log(5, 999).steps) }
        val original = document().copy(ui = document().ui.copy(logs = listOf(first, log(4))))
        writeAtomic(RuleJson.encode(original))
        val store = CommandStore(file)
        val loaded = store.load()
        assertNull(loaded.error)
        assertTrue(store.canSave)
        store.save(loaded.appState().snapshot())
        val reread = RuleJson.decode(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        assertEquals(original.ui.logs, reread.ui.logs)
    }

    @Test fun duplicateRuleUuidsWithoutSidecarsAreRejected() {
        val rule = document().rules.single()
        assertRejected(RuleDocument(rules = listOf(rule, rule)), "duplicate UUID without sidecars")
    }

    @Test fun manualRulesCannotHideTriggersOrConditions() {
        val rule = document().rules.single()
        assertRejected(document().copy(rules = listOf(rule.copy(triggers = listOf(RuleComponent("trigger.time"))))), "manual triggers")
        assertRejected(document().copy(rules = listOf(rule.copy(conditions = listOf(RuleComponent("condition.weekday"))))), "manual conditions")
    }

    @Test fun outOfRangeCategoriesAreRejected() {
        for (category in listOf(-1, 7, Int.MAX_VALUE)) {
            val base = document()
            val sidecar = base.ui.presentation.getValue(ruleId)
            assertRejected(base.copy(ui = base.ui.copy(presentation = mapOf(ruleId to sidecar.copy(category = category)))), "category=$category")
        }
    }

    @Test fun exhaustedAllocationFailsFastAndBlocksStoreWrites() {
        val base = document()
        val exhausted = base.copy(ui = base.ui.copy(presentation = mapOf(
            ruleId to RulePresentation(legacyId = Long.MAX_VALUE - 1),
        )))
        // Keep a broken allocator off both the store lock and Robolectric's main thread.
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val finished = java.util.concurrent.CountDownLatch(1)
        kotlin.concurrent.thread(isDaemon = true, name = "allocation-boundary-probe") {
            try { exhausted.toPersistedAppState() }
            catch (error: Exception) { failure.set(error) }
            finally { finished.countDown() }
        }
        assertTrue("allocator must finish rather than wrap and spin", finished.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertNotNull("allocator must reject exhausted IDs", failure.get())
        assertRejected(exhausted, "no allocatable ID above existing declarations")
    }

    @Test fun missingSidecarsReceiveDistinctIdsAboveHistoricalReferences() {
        val base = document()
        val second = base.rules.single().copy(id = LegacyCommandMigration.uuidForLegacyId(9))
        val original = base.copy(rules = base.rules + second, ui = RuleUiMetadata(logs = listOf(log(5, 2_000), log(6, 2_000))))
        writeAtomic(RuleJson.encode(original))
        val store = CommandStore(file)
        val state = requireNotNull(store.load().saved)
        val ids = state.oneTaps.flatMap { listOf(it.id) + it.actions.map { action -> action.id } }
        assertEquals(4, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it > 2_000 && it < Long.MAX_VALUE })
        store.save(state)
        assertEquals(state.oneTaps, requireNotNull(CommandStore(file).load().saved).oneTaps)
    }

    private fun log(id: Long, itemId: Long = 2) = com.flowglass.engine.PersistedLog(
        id, "校验", "手动", "12:00", true, "Bolt",
        steps = listOf(com.flowglass.engine.PersistedLogStep(itemId, "任务", "SUCCESS", "完成", 12)),
    )

    private fun document() = RuleDocument(
        rules = listOf(EngineRule(id = ruleId, name = "校验", type = com.flowglass.engine.RuleType.MANUAL, actions = listOf(RuleComponent("action.delay")))),
        ui = RuleUiMetadata(presentation = mapOf(ruleId to RulePresentation(
            legacyId = 1,
            actions = listOf(ComponentPresentation(2, "Bolt")),
        ))),
    )

    private fun assertRejected(document: RuleDocument, label: String) {
        // Exercise a successful read followed by corruption on this same store instance.
        writeAtomic(RuleJson.encode(document()))
        val store = CommandStore(file)
        assertNotNull(store.load().saved)
        assertTrue(store.canSave)
        val original = ("\n" + RuleJson.encode(document) + "\n").toByteArray()
        writeAtomic(original.toString(Charsets.UTF_8))
        val result = store.load()
        assertNotNull(label, result.error)
        assertNull(label, result.saved)
        assertFalse(label, store.canSave)
        val safe = result.appState()
        assertTrue(label, safe.mode in 0..2)
        assertTrue(label, safe.autoCmds.isEmpty() && safe.oneTaps.isEmpty())
        assertTrue(label, runCatching { store.save(safe.snapshot()) }.isFailure)
        assertTrue(label, original.contentEquals(AtomicFile(file).openRead().use { it.readBytes() }))
        assertFalse(label, File(folder.root, "commands.v1.bak.json").exists())
    }

    private fun writeAtomic(text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        stream.write(text.toByteArray())
        atomic.finishWrite(stream)
    }
}
