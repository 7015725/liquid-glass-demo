package com.flowglass.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodecRoundTripTest {
    @Test fun originalDocumentSingleRuleImportsWithoutChangingExplicitValues() {
        val text = javaClass.getResource("/document-rule.json")!!.readText()
        val bundle = RuleCodec.decodeBundle(text)
        assertEquals(1, bundle.rules.size)
        val r = bundle.rules.single()
        assertEquals("uuid", r.id)
        assertEquals("到家自动静音关闭", r.name)
        assertTrue(r.enabled)
        assertEquals(2, r.triggers.size)
        assertEquals(2, r.conditions.size)
        assertEquals(3, r.actions.size)
        assertEquals(2, r.locals.size)
        assertEquals(JsonPrimitive("{event.time}"), r.actions[1].params["value"])
        assertTrue(RuleValidator.validate(r).toString(), RuleValidator.validate(r).isEmpty())
        assertEquals(r, RuleCodec.decodeRule(RuleCodec.encodeRule(r)))
        val globals = listOf(Variable("mode", initial = JsonPrimitive("auto")))
        assertEquals(RuleBundle(rules = listOf(r), globals = globals), RuleCodec.decodeBundle(RuleCodec.exportBundle(listOf(r), globals)))
    }
    @Test fun draftRoundTripPreservesInvalidEditableValuesAndLargeIntegers() {
        val draft = Rule(name = "", enabled = true, debounceMillis = Long.MAX_VALUE, actions = listOf(Component(type = "action.loop", params = mapOf("count" to JsonPrimitive("not a number")))), locals = listOf(Variable("n", "int", JsonPrimitive(9007199254740993L))))
        assertEquals(draft, RuleCodec.decodeRule(RuleCodec.encodeRule(draft)))
    }
}
