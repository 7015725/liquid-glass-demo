package com.flowglass.demo.ui

import com.flowglass.engine.BlockKind
import com.flowglass.engine.ComponentPresentation
import com.flowglass.engine.CURRENT_FORMAT_VERSION
import com.flowglass.engine.OnError
import com.flowglass.engine.Rule as EngineRule
import com.flowglass.engine.RuleBranch
import com.flowglass.engine.RuleComponent
import com.flowglass.engine.RuleDocument
import com.flowglass.engine.RulePresentation
import com.flowglass.engine.RuleType
import com.flowglass.engine.RuleUiMetadata
import com.flowglass.engine.RuleJson
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v2 -> v3 migration: the byte-exact backup, one-shot upgrade, read-back and a save round trip.
 *
 * v3 keeps the same formatVersion field but changes the rule body to a nested tree and the UI
 * sidecar from positional lists to an id -> icon map.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskChainMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "commands.json")
    private val backup get() = File(folder.root, "commands.v2.bak.json")

    private fun v2Document() = """
        {"formatVersion":2,"rules":[
          {"id":"rule-legacy","name":"夜间静音","type":"auto","enabled":true,
           "actions":[
             {"type":"action.delay","params":{"duration":1000}},
             {"type":"action.set_var","params":{"name":"run.x","op":"set","value":"1"}}
           ]}],
         "ui":{"running":true,"mode":1,"presentation":{
           "rule-legacy":{"category":1,"icon":"Moon","legacyId":7,
             "actions":[{"id":1001,"icon":"Timer"},{"id":1002,"icon":"Bolt"}]}}}}
    """.trimIndent()

    @Test
    fun v2IsBackedUpByteForByteAndRewrittenAsV3() {
        val source = "\n" + v2Document() + "\n"
        file.writeText(source)

        val loaded = CommandStore(file).load()

        assertNotNull(loaded.saved)
        assertTrue("migration must leave a byte-exact backup", backup.isFile)
        assertEquals(source, backup.readText())
        val rewritten = JSONObject(file.readText())
        assertEquals(CURRENT_FORMAT_VERSION, rewritten.getInt("formatVersion"))
        val presentation = rewritten.getJSONObject("ui").getJSONObject("presentation").getJSONObject("rule-legacy")
        assertTrue("v3 stores icons by id", presentation.has("items"))
        assertEquals("v3 drops the positional lists", 0, presentation.getJSONArray("actions").length())
        assertEquals("Timer", presentation.getJSONObject("items").getJSONObject("1001").getString("icon"))
    }

    @Test
    fun theMigratedStateReadsBackAndASecondStartDoesNotMigrateAgain() {
        file.writeText(v2Document())
        val first = requireNotNull(CommandStore(file).load().saved)
        val backupBytes = backup.readBytes()

        val second = requireNotNull(CommandStore(file).load().saved)

        assertEquals(first, second)
        assertTrue(backupBytes.contentEquals(backup.readBytes()))
        assertFalse("a second start must not create another backup", File(folder.root, "commands.v2.bak-2.json").exists())
        assertEquals(CURRENT_FORMAT_VERSION, JSONObject(file.readText()).getInt("formatVersion"))
    }

    @Test
    fun savingTheMigratedStateRoundTripsThroughTheFile() {
        file.writeText(v2Document())
        val store = CommandStore(file)
        val state = requireNotNull(store.load().saved)

        store.save(state)
        val reopened = requireNotNull(CommandStore(file).load().saved)

        assertEquals(state, reopened)
        assertEquals(CURRENT_FORMAT_VERSION, JSONObject(file.readText()).getInt("formatVersion"))
    }

    @Test
    fun nestedBlocksSurviveADocumentRoundTrip() {
        val nested = RuleComponent(
            id = 11,
            type = "block.if",
            conditions = listOf(RuleComponent(id = 12, type = "condition.var_compare", params = mapOf(
                "left" to JsonPrimitive("%{run.x}"), "op" to JsonPrimitive("eq"), "right" to JsonPrimitive("1"),
            ))),
            children = listOf(
                RuleComponent(
                    id = 13,
                    type = "block.loop",
                    params = mapOf("mode" to JsonPrimitive("times"), "times" to JsonPrimitive(3)),
                    children = listOf(RuleComponent(id = 14, type = "action.delay", params = mapOf("duration" to JsonPrimitive(500)))),
                ),
            ),
            branches = listOf(
                RuleBranch(
                    conditions = listOf(RuleComponent(id = 15, type = "condition.charging", params = mapOf("state" to JsonPrimitive("charging")))),
                    children = listOf(RuleComponent(id = 16, type = "block.break", onError = OnError.CONTINUE)),
                ),
                RuleBranch(children = listOf(RuleComponent(id = 17, type = "action.set_var", params = mapOf(
                    "name" to JsonPrimitive("global.done"), "op" to JsonPrimitive("set"), "value" to JsonPrimitive("1"),
                )))),
            ),
        )
        val rule = EngineRule(
            id = "rule-1", name = "任务链", type = RuleType.MANUAL, enabled = true,
            actions = listOf(nested),
        )
        val document = RuleDocument(
            rules = listOf(rule),
            ui = RuleUiMetadata(presentation = mapOf(
                rule.id to RulePresentation(legacyId = 42, items = (11L..17L).associateWith { ComponentPresentation(it.toLong(), "Bolt") }),
            )),
            globals = mapOf("done" to JsonPrimitive("0")),
        )

        val encoded = RuleJson.encode(document)
        val decoded = RuleJson.decode(encoded)
        val restored = decoded.toPersistedAppState()
        val command = restored.oneTaps.single()
        val ifItem = command.actions.single()

        assertEquals("block.if", ifItem.typeId)
        // alpha8: the old else-if chain is loaded as one else branch with a nested if.
        assertEquals(1, ifItem.branches.size)
        assertTrue(ifItem.branches.single().conditions.isEmpty())
        val nestedIf = ifItem.branches.single().children.single()
        assertEquals("block.if", nestedIf.typeId)
        assertTrue(nestedIf.conditions.isNotEmpty())
        assertEquals("block.break", nestedIf.children.single().typeId)
        assertEquals("action.set_var", nestedIf.branches.single().children.single().typeId)
        assertEquals("block.loop", ifItem.children.single().typeId)
        assertEquals("action.delay", ifItem.children.single().children.single().typeId)
        assertEquals(11L, ifItem.id)
        assertEquals(mapOf("done" to JsonPrimitive("0")), restored.engineGlobals)
        assertNotNull(decoded.ui.presentation.getValue("rule-1").items[12L])

        // The first save writes the converted tree; loading that file again must be byte-stable.
        val again = AppStateJson.encode(restored)
        assertEquals(again, AppStateJson.encode(AppStateJson.decode(again)))
    }

    @Test
    fun currentFormatWritesTheTreeInTheRuleBody() {
        val document = RuleDocument(
            rules = listOf(EngineRule(
                id = "r", name = "r", type = RuleType.MANUAL, enabled = true,
                actions = listOf(RuleComponent(
                    id = 21, type = "block.loop",
                    params = mapOf("mode" to JsonPrimitive("times"), "times" to JsonPrimitive(2)),
                    children = listOf(RuleComponent(id = 22, type = "action.delay", params = mapOf("duration" to JsonPrimitive(100)))),
                )),
            )),
            ui = RuleUiMetadata(presentation = mapOf("r" to RulePresentation(legacyId = 5))),
        )
        val encoded = RuleJson.encode(document)
        val actions = JSONObject(encoded).getJSONArray("rules").getJSONObject(0).getJSONArray("actions")
        assertEquals("block.loop", actions.getJSONObject(0).getString("type"))
        assertEquals("action.delay", actions.getJSONObject(0).getJSONArray("children").getJSONObject(0).getString("type"))
        assertTrue(BlockKind.LOOP == com.flowglass.engine.BuiltInComponents.registry().find("block.loop")?.block)
    }
}
