package com.flowglass.demo.ui.editor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.flowglass.engine.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule as JunitRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewerRegressionTest {
    @get:JunitRule val compose = createComposeRule()

    private val locals = listOf(Variable("count", "int", JsonPrimitive(0)))
    private fun assignment() = ComponentRegistry.newComponent("action.variable_set").let {
        it.copy(params = it.params + ("target" to JsonPrimitive("global.scratch")) + ("value" to JsonPrimitive(0)))
    }

    private fun editAssignment(action: Component) {
        compose.onNodeWithTag("edit-${action.id}").performScrollTo().performClick()
        compose.onNodeWithTag("param-target").performScrollTo().performTextReplacement("local.count")
        compose.onNodeWithTag("param-value").performScrollTo().performTextReplacement("1")
        compose.onAllNodesWithTag("component-done").onLast().performClick()
        compose.onAllNodesWithTag("component-errors").assertCountEquals(0)
    }

    @Test fun declaredLocalCanBeAssignedFromComponentDialog() {
        val action = assignment()
        var saved: Rule? = null
        compose.setContent {
            RuleEditorScreen(Rule(name = "局部赋值", type = "manual", locals = locals, actions = listOf(action)), emptyList(), { saved = it }, {})
        }
        compose.onNodeWithTag("tab-actions").performClick()
        editAssignment(action)
        compose.onNodeWithTag("component-done").assertDoesNotExist()
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            assertEquals(locals, requireNotNull(saved).locals)
            assertEquals(JsonPrimitive("local.count"), saved!!.actions.single().params["target"])
            assertEquals(JsonPrimitive(1), saved!!.actions.single().params["value"])
        }
    }

    @Test fun declaredLocalCanBeAssignedInThenAndElseDialogs() {
        val thenAction = assignment()
        val elseAction = assignment()
        val branch = ComponentRegistry.newComponent("action.if").let {
            it.copy(params = it.params + ("expression" to JsonPrimitive("true")), children = listOf(thenAction), elseActions = listOf(elseAction))
        }
        var saved: Rule? = null
        compose.setContent {
            RuleEditorScreen(Rule(name = "嵌套局部赋值", type = "manual", locals = locals, actions = listOf(branch)), emptyList(), { saved = it }, {})
        }
        compose.onNodeWithTag("tab-actions").performClick()
        compose.onNodeWithTag("edit-${branch.id}").performScrollTo().performClick()
        editAssignment(thenAction)
        editAssignment(elseAction)
        compose.onNodeWithTag("component-done").performClick()
        compose.onAllNodesWithTag("component-errors").assertCountEquals(0)
        compose.onNodeWithTag("component-done").assertDoesNotExist()
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved).actions.single()
            for (action in result.children + result.elseActions) {
                assertEquals(JsonPrimitive("local.count"), action.params["target"])
                assertEquals(JsonPrimitive(1), action.params["value"])
            }
            assertEquals(2, result.children.size + result.elseActions.size)
        }
    }

    @Test fun overDepthJsonDraftSurvivesRealEditorRestoration() {
        val action = assignment()
        val initial = Rule(id = "json-draft", name = "JSON 草稿", type = "manual", actions = listOf(action))
        val invalid = "[".repeat(EngineLimits.MAX_VALUE_DEPTH + 1) + "0" + "]".repeat(EngineLimits.MAX_VALUE_DEPTH + 1)
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(initial, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("rule-name").performTextReplacement("未丢失的草稿名称")
        compose.onNodeWithTag("tab-actions").performClick()
        compose.onNodeWithTag("edit-${action.id}").performScrollTo().performClick()
        compose.onNodeWithTag("component-timeout").performScrollTo().performTextReplacement("45")
        compose.onNodeWithTag("param-value").performScrollTo().performTextReplacement(invalid)

        // Exercise the real RuleSaver, not a hand-written or mocked saver.
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("param-value").assertTextContains(invalid)
        compose.onNodeWithTag("parameter-errors").assertTextContains("params.value", substring = true)
        compose.onNodeWithTag("parameter-errors").assertTextContains("JSON 大小、节点或深度", substring = true)
        compose.onNodeWithTag("component-done").performClick()
        compose.onNodeWithTag("component-errors").assertTextContains("params.value", substring = true)
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("param-value").performScrollTo().performTextReplacement("[1]")
        compose.onNodeWithTag("parameter-errors").assertDoesNotExist()
        compose.onNodeWithTag("component-done").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals("未丢失的草稿名称", result.name)
            assertEquals(action.id, result.actions.single().id)
            assertEquals(45, result.actions.single().timeoutSeconds)
            assertEquals(JsonArray(listOf(JsonPrimitive(1))), result.actions.single().params["value"])
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }

    @Test fun overDepthValueStaysRawAndNeverReachesTheModel() = assertRejectedValue(
        "[".repeat(EngineLimits.MAX_VALUE_DEPTH) + "0" + "]".repeat(EngineLimits.MAX_VALUE_DEPTH))

    @Test fun overNodeLimitValueStaysRawAndNeverReachesTheModel() = assertRejectedValue(
        List(EngineLimits.MAX_JSON_NODES) { "0" }.joinToString(",", "[", "]"))

    @Test fun overUtf8ByteLimitValueStaysRawAndNeverReachesTheModel() = assertRejectedValue(
        JsonPrimitive("界".repeat(EngineLimits.MAX_BYTES / 3 + 1)).toString())

    // Value bytes alone fit, but pretty-printed rule encoding exceeds the byte budget.
    @Test fun overEncodedByteLimitValueStaysRawAndNeverReachesTheModel() = assertRejectedValue(
        List(80_000) { "0" }.joinToString(",", "[", "]"))

    private fun assertRejectedValue(raw: String) {
        val parsed = RuleCodec.json.parseToJsonElement(raw)
        val unsafe = Rule(actions = listOf(assignment().copy(params = mapOf("value" to parsed))))
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.encodeRule(unsafe) }
        val inputs = EditorInputs()
        var model: JsonElement = JsonPrimitive("previous")
        inputs.edit("params.value", raw, "json") { model = it }
        assertTrue("Invalid value must not reach the model", model == JsonPrimitive("previous"))
        assertEquals(raw, inputs.value("params.value", ""))
        assertTrue(inputs.errors.containsKey("params.value"))
        inputs.edit("params.value", "[1]", "json") { model = it }
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), model)
        assertTrue(inputs.errors.isEmpty())
    }

    @Test fun validJsonBoundaryAndNumericLexemesArePreserved() {
        val deepest = "[".repeat(EngineLimits.MAX_VALUE_DEPTH - 1) + "0" + "]".repeat(EngineLimits.MAX_VALUE_DEPTH - 1)
        val cases = listOf("json" to deepest, "list" to deepest, "dict" to "{\"count\":1}", "null" to "null",
            "json" to "1.000000000000000001", "json" to "9223372036854775808")
        for ((kind, raw) in cases) {
            val parsed = requireNotNull(parseEditorValue(kind, raw))
            assertEquals(raw, parsed.toString())
            val rule = Rule(actions = listOf(assignment().copy(params = mapOf("value" to parsed))))
            assertEquals(parsed, RuleCodec.decodeRule(RuleCodec.encodeRule(rule)).actions.single().params["value"])
        }
        val tooDeep = "[" + deepest + "]"
        for (kind in listOf("json", "list", "dict", "null")) assertNull(parseEditorValue(kind, tooDeep))
    }

    @Test fun undeclaredLocalsAndWrongAssignmentTypesStillBlockCompletion() {
        val action = assignment()
        var saves = 0
        compose.setContent {
            RuleEditorScreen(Rule(name = "局部变量校验", type = "manual", locals = locals, actions = listOf(action)), emptyList(), { saves++ }, {})
        }
        compose.onNodeWithTag("tab-actions").performClick()
        compose.onNodeWithTag("edit-${action.id}").performScrollTo().performClick()
        compose.onNodeWithTag("param-target").performScrollTo().performTextReplacement("local.count")
        compose.onNodeWithTag("param-value").performScrollTo().performTextReplacement("\"wrong type\"")
        compose.onNodeWithTag("component-done").performClick()
        compose.onNodeWithTag("component-errors").assertTextContains("类型不符", substring = true)
        compose.onNodeWithTag("param-target").performScrollTo().performTextReplacement("local.missing")
        compose.onNodeWithTag("param-value").performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("component-done").performClick()
        compose.onNodeWithTag("component-errors").assertTextContains("局部变量未声明：missing", substring = true)
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun declaredLocalCanBeReferencedFromConditionDialog() {
        val condition = ComponentRegistry.newComponent("condition.variable").let {
            it.copy(params = it.params + ("name" to JsonPrimitive("global.scratch")) + ("op" to JsonPrimitive("eq")) + ("value" to JsonPrimitive(1)))
        }
        var saved: Rule? = null
        compose.setContent {
            RuleEditorScreen(Rule(name = "局部变量条件", locals = locals,
                triggers = listOf(ComponentRegistry.newComponent("trigger.screen")), conditions = listOf(condition),
                actions = listOf(ComponentRegistry.newComponent("action.stop"))), emptyList(), { saved = it }, {})
        }
        compose.onNodeWithTag("tab-conditions").performClick()
        compose.onNodeWithTag("edit-${condition.id}").performScrollTo().performClick()
        compose.onNodeWithTag("param-name").performScrollTo().performTextReplacement("local.count")
        compose.onNodeWithTag("component-done").performClick()
        compose.onAllNodesWithTag("component-errors").assertCountEquals(0)
        compose.onNodeWithTag("component-done").assertDoesNotExist()
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            assertEquals(JsonPrimitive("local.count"), requireNotNull(saved).conditions.single().params["name"])
        }
    }
}
