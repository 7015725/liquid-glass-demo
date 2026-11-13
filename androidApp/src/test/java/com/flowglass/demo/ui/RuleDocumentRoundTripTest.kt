package com.flowglass.demo.ui

import android.util.AtomicFile
import com.flowglass.engine.ComponentPresentation
import com.flowglass.engine.RuleType
import com.flowglass.engine.RunMode
import com.flowglass.engine.MatchMode
import com.flowglass.engine.OnError
import com.flowglass.engine.LegacyCommandMigration
import com.flowglass.engine.Rule as EngineRule
import com.flowglass.engine.RuleComponent
import com.flowglass.engine.RuleDocument
import com.flowglass.engine.RuleJson
import com.flowglass.engine.RulePresentation
import com.flowglass.engine.RuleUiMetadata
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class RuleDocumentRoundTripTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "commands.json")
    private val ruleId = LegacyCommandMigration.uuidForLegacyId(1)

    @Test fun knownParameterKeyWinsOverExtensionOrder() {
        val document = document(RuleComponent("action.delay", linkedMapOf(
            "extension" to JsonPrimitive("KEEP"), "duration" to JsonPrimitive(1000),
        )))
        val store = open(document)
        val state = requireNotNull(store.load().saved)
        assertEquals("1 秒", state.oneTaps.single().actions.single().parameter)
        store.save(state)
        assertEquals(document.rules, readDocument().rules)
    }

    @Test fun authoritativeEmptyTextWinsOverStaleSidecar() {
        val document = document(RuleComponent("action.copy_clipboard", mapOf("text" to JsonPrimitive(""))), "OLD TEXT")
        val store = open(document)
        val state = requireNotNull(store.load().saved)
        assertEquals("", state.oneTaps.single().actions.single().parameter)
        store.save(state)
        assertEquals(document.rules, readDocument().rules)
    }

    @Test fun unchangedTypedValuesAndRuleSemanticsSurviveRealStoreRoundTrip() {
        val values = listOf(
            JsonPrimitive(true), JsonPrimitive(42), kotlinx.serialization.json.JsonNull,
            RuleJson.format.parseToJsonElement("[1,false,null,{\"x\":2}]"),
            RuleJson.format.parseToJsonElement("{\"nested\":[true,3]}"), JsonPrimitive(""),
        )
        val actions = values.map { RuleComponent("action.copy_clipboard", mapOf("text" to JsonPrimitive("保留"), "extension" to it), onError = OnError.CONTINUE) } +
            RuleComponent("action.post_notification", mapOf("title" to JsonPrimitive("标题"), "content" to JsonPrimitive("正文"), "extension" to values[3])) +
            RuleComponent("legacy.unknown", mapOf("raw" to values[4]))
        val original = document(actions.first()).let { base -> base.copy(
            rules = listOf(base.rules.single().copy(
                type = RuleType.AUTO, runMode = RunMode.QUEUE,
                triggerMatch = com.flowglass.engine.MatchSpec(mode = MatchMode.CUSTOM, atLeast = 1, windowSeconds = 37),
                conditionMatch = com.flowglass.engine.MatchSpec(mode = MatchMode.ALL, windowSeconds = 91),
                triggers = listOf(RuleComponent("trigger.time", mapOf("time" to JsonPrimitive("08:00"), "extension" to values[4]))),
                conditions = listOf(RuleComponent("condition.weekday", mapOf("days" to JsonPrimitive("workday"), "extension" to values[3]))),
                locals = listOf(com.flowglass.engine.LocalVariable("payload", "object", values[4], true)),
                actions = actions,
            )),
            ui = RuleUiMetadata(),
        ) }
        val store = open(original)
        val app = store.load().appState()
        store.save(app.snapshot())
        assertEquals(original.rules, readDocument().rules)
        assertEquals(original.rules, requireNotNull(CommandStore(file).load().saved).engineRules)
    }

    @Test fun unchangedMissingParametersAreNotMaterializedFromPresentation() {
        val original = document(RuleComponent("action.delay", mapOf("extension" to JsonPrimitive(true))), "old UI only")
        val store = open(original)
        store.save(store.load().appState().snapshot())
        assertEquals(original.rules, readDocument().rules)
    }

    @Test fun editingNotificationBodyKeepsUntouchedTypedTitleAndExtensions() {
        val action = RuleComponent("action.post_notification", mapOf(
            "title" to JsonPrimitive("false"), "content" to JsonPrimitive("old body"),
            "extension" to RuleJson.format.parseToJsonElement("{\"keep\":[1,null]}"),
        ), OnError.RETRY)
        val original = document(action)
        val store = open(original)
        val app = store.load().appState()
        val command = app.oneTaps.single()
        app.setItemParameter(command.id, CommandSection.ACTIONS, command.actions.single().id, "false|new body")
        store.save(app.snapshot())
        assertEquals(action.copy(params = action.params + ("content" to JsonPrimitive("new body"))), readDocument().rules.single().actions.single())
    }

    @Test fun editingNotificationTitleKeepsUntouchedStructuredBody() {
        val action = RuleComponent("action.post_notification", mapOf(
            "title" to JsonPrimitive("old title"), "content" to JsonPrimitive("[1,false,null]"),
            "extension" to RuleJson.format.parseToJsonElement("[1,false,null]"),
        ), OnError.CONTINUE)
        val store = open(document(action))
        val app = store.load().appState()
        val command = app.oneTaps.single()
        assertEquals("old title|[1,false,null]", command.actions.single().parameter)
        app.setItemParameter(command.id, CommandSection.ACTIONS, command.actions.single().id, "new title|[1,false,null]")
        store.save(app.snapshot())
        assertEquals(action.copy(params = action.params + ("title" to JsonPrimitive("new title"))), readDocument().rules.single().actions.single())
    }

    @Test fun legacyStructuredParameterIsReadByKeyRatherThanStalePresentation() {
        for (value in listOf("[1,false,null]", "{\"x\":2}")) {
            val original = document(RuleComponent("legacy.unknown", mapOf(
                "raw" to JsonPrimitive("OLD RAW"), "parameter" to RuleJson.format.parseToJsonElement(value),
            )), "OLD TEXT")
            val store = open(original)
            val state = requireNotNull(store.load().saved)
            assertEquals(value, state.oneTaps.single().actions.single().parameter)
            store.save(state)
            assertEquals(original.rules, readDocument().rules)
        }
    }

    @Test fun editingOneParameterAfterReorderingKeepsOtherJsonAndRuleFields() {
        val untouched = RuleComponent("action.copy_clipboard", mapOf("text" to JsonPrimitive("false"), "extension" to JsonPrimitive(false)), OnError.CONTINUE)
        val edited = RuleComponent("action.delay", mapOf(
            "duration" to JsonPrimitive(1), "extension" to RuleJson.format.parseToJsonElement("{\"keep\":[true,null]}"),
        ), OnError.RETRY)
        val base = document(untouched)
        val rule = base.rules.single().copy(
            runMode = RunMode.QUEUE, actions = listOf(untouched, edited),
            triggerMatch = com.flowglass.engine.MatchSpec(windowSeconds = 17),
            conditionMatch = com.flowglass.engine.MatchSpec(mode = MatchMode.ALL, windowSeconds = 29),
            locals = listOf(com.flowglass.engine.LocalVariable("n", "integer", JsonPrimitive(7))),
        )
        val store = open(base.copy(rules = listOf(rule), ui = RuleUiMetadata()))
        val app = store.load().appState()
        val command = app.oneTaps.single()
        app.moveItem(command.id, CommandSection.ACTIONS, command.actions[1].id, -1)
        app.setItemParameter(command.id, CommandSection.ACTIONS, command.actions[1].id, "2 秒")
        app.setTitle(command.id, "改名")
        store.save(app.snapshot())
        val expected = rule.copy(name = "改名", actions = listOf(
            edited.copy(params = edited.params + ("duration" to JsonPrimitive(2000))), untouched,
        ))
        assertEquals(expected, readDocument().rules.single())
        val reloaded = CommandStore(file)
        reloaded.save(reloaded.load().appState().snapshot())
        assertEquals(expected, readDocument().rules.single())
    }

    @Test fun editingNotificationBodyDoesNotInventMissingTitle() {
        val action = RuleComponent("action.post_notification", mapOf("content" to JsonPrimitive("old")))
        val store = open(document(action))
        val app = store.load().appState()
        val command = app.oneTaps.single()
        app.setItemParameter(command.id, CommandSection.ACTIONS, command.actions.single().id, "|new")
        store.save(app.snapshot())
        assertEquals(action.copy(params = mapOf("content" to JsonPrimitive("new"))), readDocument().rules.single().actions.single())
    }

    private fun document(action: RuleComponent, sidecarParameter: String = "stale") = RuleDocument(
        rules = listOf(EngineRule(id = ruleId, name = "往返", type = RuleType.MANUAL, actions = listOf(action))),
        ui = RuleUiMetadata(presentation = mapOf(ruleId to RulePresentation(
            legacyId = 1,
            actions = listOf(ComponentPresentation(2, "Bolt")),
        ))),
    )

    private fun open(document: RuleDocument): CommandStore {
        val original = RuleJson.encode(document).toByteArray()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        stream.write(original)
        atomic.finishWrite(stream)
        val store = CommandStore(file)
        val loaded = store.load()
        assertNull(loaded.error)
        assertTrue(store.canSave)
        assertTrue(original.contentEquals(atomic.openRead().use { it.readBytes() }))
        assertFalse(File(folder.root, "commands.v1.bak.json").exists())
        return store
    }

    private fun readDocument() = RuleJson.decode(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
}
