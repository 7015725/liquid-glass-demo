package com.flowglass.demo.ui

import com.flowglass.engine.BlockKind
import com.flowglass.engine.ComponentPresentation
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Current-format nested block and rule-body round trips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskChainMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "commands.json")

    @Test
    fun nestedBlocksSurviveADocumentRoundTrip() {
        val nested = RuleComponent(
            id = 11,
            type = "block.if",
            conditions = listOf(RuleComponent(id = 12, type = "condition.variable", params = mapOf(
                "left" to JsonPrimitive("{x}"), "op" to JsonPrimitive("eq"), "right" to JsonPrimitive("1"),
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
                    "name" to JsonPrimitive("done"), "type" to JsonPrimitive("text"), "op" to JsonPrimitive("set"), "value" to JsonPrimitive("1"), "scope" to JsonPrimitive("global"), "retain" to JsonPrimitive("false"),
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
