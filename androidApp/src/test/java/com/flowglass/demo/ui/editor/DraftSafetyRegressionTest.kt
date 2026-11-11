package com.flowglass.demo.ui.editor

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
import org.robolectric.annotation.Config
import android.os.Looper
import org.robolectric.Shadows.shadowOf


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DraftSafetyRegressionTest {
    @get:JunitRule val compose = createComposeRule()

    private fun assignment(id: String) = ComponentRegistry.newComponent("action.variable_set").let {
        it.copy(id = id, params = it.params + ("target" to JsonPrimitive("global.scratch")) + ("value" to JsonPrimitive(0)))
    }

    private fun largeRaw() = "[".repeat(EngineLimits.MAX_VALUE_DEPTH - 2) +
        List(2_100) { "0" }.joinToString(",", "[", "]") + "]".repeat(EngineLimits.MAX_VALUE_DEPTH - 2)

    /** Nearly fills the *official pretty-printed* budget using compact UI text. */
    private fun nearBudget(initial: Rule, reserve: Int = 16): Rule {
        var count = 3_600
        fun withPadding(padding: Int): Rule {
            var value: JsonElement = JsonArray(listOf(JsonPrimitive("x".repeat(padding))) + List(count) { JsonPrimitive(0) })
            repeat(EngineLimits.MAX_VALUE_DEPTH - 2) { value = JsonArray(listOf(value)) }
            val action = initial.actions.first()
            return initial.copy(actions = listOf(action.copy(params = action.params + ("value" to value))) + initial.actions.drop(1))
        }
        fun size() = RuleCodec.encodeRule(withPadding(0)).toByteArray(Charsets.UTF_8).size
        val base = size()
        count++
        val stride = size() - base
        count += (EngineLimits.MAX_BYTES - reserve - size()) / stride
        val remaining = EngineLimits.MAX_BYTES - reserve - size()
        assertTrue(remaining > 0)
        return withPadding(remaining).also {
            assertEquals(EngineLimits.MAX_BYTES - reserve, RuleCodec.encodeRule(it).toByteArray(Charsets.UTF_8).size)
        }
    }

    private fun open(action: Component) = compose.onNodeWithTag("edit-${action.id}").performScrollTo().performClick()
    private fun value(raw: String) = compose.onNodeWithTag("param-value").performScrollTo().performTextReplacement(raw)
    private fun done() = compose.onAllNodesWithTag("component-done").onLast().performClick()

    private fun restore(restoration: StateRestorationTester) {
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
    }

    @Test fun multipleParametersShareTheHolderBudgetAndRejectedRawSurvives() {
        val initial = nearBudget(Rule(name = "多参数", type = "manual", actions = listOf(assignment("multi"))), reserve = 256)
        val action = initial.actions.single()
        val rejected = "global." + "a".repeat(1_024)
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(initial, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        open(action)
        compose.onNodeWithTag("param-target").performScrollTo().performTextReplacement(rejected)
        restore(restoration)
        compose.onNodeWithTag("param-target").assertTextContains(rejected)
        compose.onNodeWithTag("parameter-errors").assertTextContains("1 MiB", substring = true)
        done()
        compose.onNodeWithTag("component-errors").assertTextContains("params.target", substring = true)
        compose.onNodeWithTag("param-target").performScrollTo().performTextReplacement("global.ok")
        done()
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals(action.params["value"], result.actions.single().params["value"])
            assertEquals(JsonPrimitive("global.ok"), result.actions.single().params["target"])
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }

    @Test fun nestedMergeRejectionKeepsChildDialogAndBothBranches() {
        val first = assignment("nested-first")
        val second = assignment("nested-second")
        val branch = ComponentRegistry.newComponent("action.if").let {
            it.copy(params = it.params + ("expression" to JsonPrimitive("true")), children = listOf(first), elseActions = listOf(second))
        }
        val initial = Rule(name = "嵌套预算", type = "manual", actions = listOf(branch))
        val raw = largeRaw()
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(initial, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        open(branch)
        open(first); value(raw); done()
        open(second); value(raw); done()
        compose.onNodeWithTag("param-value").assertTextContains(raw)
        compose.onAllNodesWithTag("component-errors").onLast().assertTextContains("1 MiB", substring = true)
        restore(restoration)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("param-value").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("param-value").onLast().assertTextContains(raw)
        compose.onAllNodesWithTag("component-errors").onLast().assertTextContains("1 MiB", substring = true)
        value("[2]"); done(); done()
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            val edited = result.actions.single()
            assertEquals(branch.id, edited.id)
            assertEquals(first.id, edited.children.single().id)
            assertEquals(second.id, edited.elseActions.single().id)
            assertEquals(parseEditorValue("json", raw), edited.children.single().params["value"])
            assertEquals(JsonArray(listOf(JsonPrimitive(2))), edited.elseActions.single().params["value"])
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }

    @Test fun copyingCannotBypassTheWholeRuleBudget() {
        val raw = largeRaw()
        val action = assignment("copy-large").let { it.copy(params = it.params + ("value" to requireNotNull(parseEditorValue("json", raw)))) }
        val initial = Rule(name = "复制预算", type = "manual", actions = listOf(action))
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(initial, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        compose.onNodeWithTag("copy-${action.id}").performScrollTo().performClick()
        restore(restoration)
        compose.onNodeWithTag("actions-notice").assertTextContains("1 MiB", substring = true)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle { assertEquals(initial, saved) }
    }

    @Test fun localVariableMergeKeepsRawErrorAndExistingActionUntilCorrection() {
        val raw = largeRaw()
        val action = assignment("local-budget").let { it.copy(params = it.params + ("value" to requireNotNull(parseEditorValue("json", raw)))) }
        val initial = Rule(name = "局部预算", type = "manual", actions = listOf(action))
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(initial, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-locals").performClick()
        compose.onNodeWithTag("local-add").performScrollTo().performClick()
        compose.onNodeWithTag("variable-name").performScrollTo().performTextReplacement("items")
        compose.onNodeWithTag("variable-type-list").performScrollTo().performClick()
        compose.onNodeWithTag("variable-initial").performScrollTo().performTextReplacement(raw)
        compose.onNodeWithTag("variable-save").performClick()
        restore(restoration)
        compose.onNodeWithTag("variable-initial").assertTextContains(raw)
        compose.onNodeWithTag("variable-errors").assertTextContains("1 MiB", substring = true)
        compose.onNodeWithTag("variable-initial").performScrollTo().performTextReplacement("[3]")
        compose.onNodeWithTag("variable-save").performClick()
        compose.onNodeWithTag("variable-save").assertDoesNotExist()
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals(initial.actions, result.actions)
            assertEquals("items", result.locals.single().name)
            assertEquals(JsonArray(listOf(JsonPrimitive(3))), result.locals.single().initial)
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }

    @Test fun basicTextUpdateKeepsRejectedRawInsteadOfPoisoningDraft() {
        val initial = nearBudget(Rule(name = "基础", type = "manual", actions = listOf(assignment("basic-budget"))))
        val raw = "n".repeat(512)
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(initial, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("rule-name").performTextReplacement(raw)
        restore(restoration)
        compose.onNodeWithTag("rule-name").assertTextContains(raw)
        compose.onNodeWithTag("validation-errors").assertTextContains("1 MiB", substring = true)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("rule-name").performTextReplacement("ok")
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle { assertEquals(initial.copy(name = "ok"), saved) }
    }

    @Test fun matchTreeUpdateUsesTheSameAggregateBudget() {
        val trigger = ComponentRegistry.newComponent("trigger.screen")
        val tree = MatchNode(op = "and", children = listOf(MatchNode(ref = trigger.id)))
        val initial = nearBudget(Rule(name = "匹配预算", triggers = listOf(trigger),
            triggerMatch = MatchSpec(mode = "custom", tree = tree), actions = listOf(assignment("tree-budget"))))
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(initial, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-triggers").performClick()
        compose.onNodeWithTag("tree-root-add").performScrollTo().performClick()
        restore(restoration)
        compose.onNodeWithTag("validation-errors").assertTextContains("1 MiB", substring = true)
        compose.onNodeWithTag("tree-root-remove-1").assertDoesNotExist()
        compose.onNodeWithTag("triggerMatch-all").performScrollTo().performClick()
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals(initial.actions, result.actions)
            assertEquals("all", result.triggerMatch.mode)
            assertNull(result.triggerMatch.tree)
        }
    }

    @Test fun veryDeepRawJsonIsRejectedAndCanBeCorrectedSavedAndRestored() {
        val raw = "[".repeat(10_000) + "0" + "]".repeat(10_000)
        assertTrue(raw.toByteArray(Charsets.UTF_8).size < EngineLimits.MAX_BYTES)
        val action = assignment("deep-input")
        val initial = Rule(name = "深层输入", type = "manual", actions = listOf(action))
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(initial, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        open(action)
        value(raw)
        compose.onNodeWithTag("parameter-errors").assertTextContains("params.value", substring = true)
        compose.onNodeWithTag("param-value").assertTextContains(raw)
        restore(restoration)
        compose.onNodeWithTag("param-value").assertTextContains(raw)
        done()
        compose.onNodeWithTag("component-errors").assertTextContains("params.value", substring = true)
        value("[1]")
        done()
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals(initial.name, result.name)
            assertEquals(JsonArray(listOf(JsonPrimitive(1))), result.actions.single().params["value"])
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }

    @Test fun individuallyAcceptedValuesCannotPoisonWholeDraftAndRejectedEditIsRecoverable() {
        val actions = listOf(assignment("first"), assignment("second"))
        val initial = Rule(name = "聚合大小", type = "manual", actions = actions)
        val raw = largeRaw()
        val parsed = requireNotNull(parseEditorValue("json", raw))
        val one = initial.copy(actions = listOf(actions.first().copy(params = actions.first().params + ("value" to parsed))))
        val size = RuleCodec.encodeRule(one).toByteArray(Charsets.UTF_8).size
        println("individually accepted rule bytes=$size, raw chars=${raw.length}")
        assertTrue(size > EngineLimits.MAX_BYTES / 2)
        assertTrue(size < EngineLimits.MAX_BYTES)
        var saved: Rule? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RuleEditorScreen(initial, emptyList(), { saved = it }, {}) }
        compose.onNodeWithTag("tab-actions").performClick()
        for (action in actions) {
            open(action)
            value(raw)
            done()
        }
        // The original defect throws from the real RuleSaver here.
        restore(restoration)
        compose.onNodeWithTag("param-value").assertTextContains(raw)
        compose.onNodeWithTag("component-errors").assertTextContains("1 MiB", substring = true)
        compose.runOnIdle { assertNull(saved) }
        value("[2]")
        done()
        compose.onNodeWithTag("component-done").assertDoesNotExist()
        restore(restoration)
        compose.onNodeWithTag("rule-save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals(initial.name, result.name)
            assertEquals(actions.map { it.id }, result.actions.map { it.id })
            assertEquals(parsed, result.actions[0].params["value"])
            assertEquals(JsonArray(listOf(JsonPrimitive(2))), result.actions[1].params["value"])
            assertEquals(result, RuleCodec.decodeRule(RuleCodec.encodeRule(result)))
        }
    }
}
