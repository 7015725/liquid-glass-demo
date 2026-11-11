package com.flowglass.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StorageSafetyTest {
    private val base = Rule(name = "x", type = "manual", actions = listOf(Component(type = "action.stop")))
    @Test fun oversizedExportsAndDraftsFailRatherThanTruncateOrBecomeUnreadable() {
        val huge = base.copy(description = "中".repeat(400000))
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.encodeRule(huge) }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.exportBundle(listOf(base.copy(id = "a", description = "x".repeat(600000)), base.copy(id = "b", description = "y".repeat(600000)))) }
        assertTrue(RuleValidator.validate(huge).any { it.path == "rule" })
    }
    @Test fun storageDecodeCannotBypassComponentCountLimits() {
        val text = "{\"name\":\"draft\",\"actions\":[" + List(1001) { "{\"type\":\"action.stop\"}" }.joinToString(",") + "]}"
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeRule(text) }
        val draft = RuleCodec.decodeRule("{\"actions\":[{\"type\":\"action.loop\",\"params\":{\"count\":\"bad input\"}}]}")
        assertEquals(JsonPrimitive("bad input"), draft.actions.single().params["count"])
    }
    @Test fun draftStructuralLimitsApplyBeforeRecursiveSerialization() {
        var c = Component(type = "action.stop")
        repeat(32) { c = Component(type = "action.loop", children = listOf(c)) }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.encodeRule(base.copy(actions = listOf(c))) }
        var tree = MatchNode(ref = "missing")
        repeat(32) { tree = MatchNode(op = "not", children = listOf(tree)) }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.encodeRule(base.copy(triggerMatch = MatchSpec(mode = "custom", tree = tree))) }
    }
    @Test fun nonFiniteJsonNumbersAreNotStoredInCollections() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { n ->
            val variable = Variable("list", "list", JsonArray(listOf(JsonPrimitive(n))))
            assertTrue(RuleValidator.validateVariable(variable).isNotEmpty())
            assertThrows(IllegalArgumentException::class.java) { RuleCodec.encodeRule(base.copy(locals = listOf(variable))) }
        }
    }
    @Test fun genericJsonNumbersAndEscapesRoundTripExactly() {
        val value = Json.parseToJsonElement("[9223372036854775808,1e999,1.000000000000000001,{\"quote\":\"a\\\"b\\\\c\\n\"}]")
        val rule = base.copy(locals = listOf(Variable("values", "list", value)))
        val text = RuleCodec.exportBundle(listOf(rule))
        assertEquals(value, RuleCodec.decodeBundle(text).rules.single().locals.single().initial)
    }
}
