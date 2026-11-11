package com.flowglass.demo.ui.editor

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.flowglass.engine.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule as JunitRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportedComponentIdRegressionTest {
    @get:JunitRule val compose = createComposeRule()

    // Keep the original reproducer's depth, size and 64-byte headroom unchanged.
    private fun nearBudget(initial: Rule, reserve: Int): Rule {
        var count = 3_600
        fun withPadding(padding: Int): Rule {
            var value: JsonElement = JsonArray(listOf(JsonPrimitive("x".repeat(padding))) + List(count) { JsonPrimitive(0) })
            repeat(EngineLimits.MAX_VALUE_DEPTH - 2) { value = JsonArray(listOf(value)) }
            val action = initial.actions.single()
            return initial.copy(actions = listOf(action.copy(params = action.params + ("value" to value))))
        }
        fun size() = RuleCodec.encodeRule(withPadding(0)).toByteArray(Charsets.UTF_8).size
        val base = size()
        count++
        val stride = size() - base
        count += (EngineLimits.MAX_BYTES - reserve - size()) / stride
        return withPadding(EngineLimits.MAX_BYTES - reserve - size())
    }

    private fun assignment(id: String, target: String, value: JsonElement) =
        ComponentRegistry.newComponent("action.variable_set").let {
            it.copy(id = id, params = it.params + mapOf("target" to JsonPrimitive(target), "value" to value))
        }

    private fun open(component: Component) = compose.onNodeWithTag("edit-${component.id}").performScrollTo().performClick()
    private fun target(text: String) = compose.onNodeWithTag("param-target").performScrollTo().performTextReplacement(text)
    private fun done() = compose.onAllNodesWithTag("component-done").onLast().performClick()

    private fun restore(restoration: StateRestorationTester) {
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
    }

    @Test fun unfinishedNearBudgetLongIdFormRestoresThenEditsAndSaves() {
        val action = assignment("a".repeat(128), "global.scratch", JsonPrimitive(0))
        val initial = nearBudget(Rule(name = "检查", type = "manual", actions = listOf(action)), 64)
        val encoded = RuleCodec.encodeRule(initial)
        assertEquals(1_048_512, encoded.toByteArray(Charsets.UTF_8).size)
        val imported = RuleCodec.decodeBundle(encoded).rules.single()
        val original = imported.actions.single()
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(imported, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        open(original)
        target("global.draft")
        restore(restoration)
        compose.onNodeWithTag("component-done").assertExists()
        compose.onNodeWithTag("param-target").assertTextContains("global.draft")
        compose.onNodeWithTag("param-value").assertTextContains(original.params.getValue("value").toString())
        compose.runOnIdle { assertNull(saved) }
        target("global.ok")
        done()
        compose.onNodeWithTag("component-done").assertDoesNotExist()
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            val expected = original.copy(params = original.params + ("target" to JsonPrimitive("global.ok")))
            assertEquals(imported.copy(actions = listOf(expected)), result)
            assertEquals(original.id, result.actions.single().id)
            assertEquals(original.params["value"], result.actions.single().params["value"])
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }

    @Test fun sequentialLongEscapedAndUnicodeIdsDoNotShareDrafts() {
        val first = assignment("a".repeat(128), "global.first_original",
            RuleCodec.decodeJsonValue("""{"number":9007199254740993,"decimal":1.2300,"zero":-0}"""))
        val second = assignment("组\"\\文".repeat(32), "global.second_original",
            RuleCodec.decodeJsonValue("""{"text":"引号\"和反斜线\\","items":[true,null,"二"]}"""))
        assertEquals(128, second.id.length)
        assertTrue(second.id.toByteArray(Charsets.UTF_8).size > second.id.length)
        val imported = RuleCodec.decodeBundle(RuleCodec.encodeRule(
            Rule(name = "独立草稿", type = "manual", actions = listOf(first, second)))).rules.single()
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(imported, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        open(first)
        target("global.a_draft")
        restore(restoration)
        compose.onNodeWithTag("param-target").assertTextContains("global.a_draft")
        target("global.a")
        done()
        open(second)
        compose.onNodeWithTag("param-target").assertTextContains("global.second_original")
        compose.onNodeWithTag("param-value").assertTextContains(second.params.getValue("value").toString())
        target("global.b_draft")
        restore(restoration)
        compose.onNodeWithTag("param-target").assertTextContains("global.b_draft")
        target("global.b")
        done()
        // Reopen A after B in the same list/call site; both model and raw input must be A's.
        open(first)
        compose.onNodeWithTag("param-target").assertTextContains("global.a")
        compose.onNodeWithTag("param-value").assertTextContains(first.params.getValue("value").toString())
        target("global.a2")
        done()
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals(imported.copy(actions = listOf(
                first.copy(params = first.params + ("target" to JsonPrimitive("global.a2"))),
                second.copy(params = second.params + ("target" to JsonPrimitive("global.b")))
            )), result)
            assertEquals(listOf(first.id, second.id), result.actions.map { it.id })
            assertEquals(imported.actions.map { it.params["value"] }, result.actions.map { it.params["value"] })
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }

    @Test fun oneCharacterAndUuidIdsRemainEditable() {
        val uuid = ComponentRegistry.newComponent("action.variable_set").id
        assertEquals(36, uuid.length)
        val actions = listOf(assignment("x", "global.original_a", JsonPrimitive(1)),
            assignment(uuid, "global.original_b", JsonPrimitive("unchanged")))
        val imported = RuleCodec.decodeBundle(RuleCodec.encodeRule(
            Rule(name = "短ID与UUID", type = "manual", actions = actions))).rules.single()
        var saved: Rule? = null
        compose.setContent { RuleEditorScreen(imported, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        actions.forEachIndexed { index, action ->
            open(action)
            compose.onNodeWithTag("param-target").assertTextContains(action.params.getValue("target").jsonPrimitive.content)
            target("global.v$index")
            done()
        }
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals(imported.copy(actions = actions.mapIndexed { index, action ->
                action.copy(params = action.params + ("target" to JsonPrimitive("global.v$index")))
            }), result)
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }

    @Test fun unfinishedNestedLongIdFormRestoresWithoutChangingParentOrSibling() {
        val child = assignment("子\"\\项".repeat(32), "global.original", JsonArray(listOf(JsonPrimitive("保留"), JsonNull)))
        val sibling = ComponentRegistry.newComponent("action.stop").copy(id = "s".repeat(128))
        val branch = ComponentRegistry.newComponent("action.if").let {
            it.copy(id = "p".repeat(128), params = it.params + ("expression" to JsonPrimitive("true")),
                children = listOf(child), elseActions = listOf(sibling))
        }
        val imported = RuleCodec.decodeBundle(RuleCodec.encodeRule(
            Rule(name = "嵌套恢复", type = "manual", actions = listOf(branch)))).rules.single()
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(imported, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        open(branch)
        open(child)
        target("global.draft")
        restore(restoration)
        compose.onAllNodesWithTag("component-done").assertCountEquals(2)
        compose.onNodeWithTag("param-target").assertTextContains("global.draft")
        target("global.ok")
        done()
        done()
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            val edited = child.copy(params = child.params + ("target" to JsonPrimitive("global.ok")))
            assertEquals(imported.copy(actions = listOf(branch.copy(children = listOf(edited)))), result)
            assertEquals(sibling, result.actions.single().elseActions.single())
            assertEquals(child.id, result.actions.single().children.single().id)
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }

    @Test fun importedNearBudgetLongIdCanOpenEditAndSaveWithoutChangingItsValue() {
        val action = ComponentRegistry.newComponent("action.variable_set").let {
            it.copy(id = "a".repeat(128), params = it.params + ("target" to JsonPrimitive("global.scratch")))
        }
        val initial = nearBudget(Rule(name = "检查", type = "manual", actions = listOf(action)), 64)
        assertTrue(RuleValidator.validate(initial).isEmpty())
        val encoded = RuleCodec.encodeRule(initial)
        val component = initial.actions.single()
        val bridgeEncoded = RuleCodec.encodeRule(Rule(actions = listOf(component)))
        assertEquals(128, component.id.length)
        assertEquals(1_048_512, encoded.toByteArray(Charsets.UTF_8).size)
        assertEquals(1_048_504, bridgeEncoded.toByteArray(Charsets.UTF_8).size)
        println("initial_bytes=${encoded.toByteArray(Charsets.UTF_8).size}; bridge_bytes=${bridgeEncoded.toByteArray(Charsets.UTF_8).size}; component_id_length=${component.id.length}")
        assertEquals(initial, RuleCodec.decodeRule(encoded))
        // Exercise an accepted import and the real edit button, not a synthetic holder.
        val imported = RuleCodec.decodeBundle(encoded).rules.single()
        var saved: Rule? = null
        compose.setContent { RuleEditorScreen(imported, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        compose.onNodeWithTag("edit-${action.id}").performScrollTo().performClick()
        compose.onNodeWithTag("component-done").assertExists()
        compose.onNodeWithTag("param-target").performScrollTo().performTextReplacement("global.ok")
        compose.onNodeWithTag("component-done").performClick()
        compose.onNodeWithTag("component-done").assertDoesNotExist()
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            val expected = component.copy(params = component.params + ("target" to JsonPrimitive("global.ok")))
            assertEquals(imported.copy(actions = listOf(expected)), result)
            assertEquals(component.id, result.actions.single().id)
            assertEquals(component.params["value"], result.actions.single().params["value"])
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }
}
