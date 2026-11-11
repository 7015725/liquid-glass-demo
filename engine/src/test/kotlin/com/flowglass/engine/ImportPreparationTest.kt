package com.flowglass.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImportPreparationTest {
    private fun call(id: String) = Component(type = "action.call_manual", params = mapOf("ruleId" to JsonPrimitive(id)))
    private val target = Rule(id = "target", name = "target", type = "manual", enabled = true, actions = listOf(Component(type = "action.stop")))
    @Test fun importsReceiveFreshDisabledIdsAndNestedCallsAreRemapped() {
        val branch = Component(type = "action.if", params = mapOf("expression" to JsonPrimitive("true")), children = listOf(call("target")), elseActions = listOf(call("external")))
        val caller = Rule(id = "caller", name = "caller", type = "manual", enabled = true, actions = listOf(branch))
        val bundle = RuleBundle(rules = listOf(caller, target), globals = listOf(Variable("n", "int", JsonPrimitive(1))))
        val imported = RuleCodec.prepareImport(bundle)
        assertEquals(2, imported.rules.size)
        assertTrue(imported.rules.none { it.enabled })
        assertEquals(2, imported.rules.map { it.id }.distinct().size)
        assertTrue(imported.rules.none { it.id in setOf("caller", "target") })
        assertEquals(JsonPrimitive(imported.rules[1].id), imported.rules[0].actions[0].children[0].params["ruleId"])
        assertEquals(JsonPrimitive("external"), imported.rules[0].actions[0].elseActions[0].params["ruleId"])
        assertEquals(branch.id, imported.rules[0].actions[0].id)
        assertEquals(bundle.globals, imported.globals)
        assertTrue(bundle.rules.all { it.enabled })
        assertEquals(JsonPrimitive("target"), bundle.rules[0].actions[0].children[0].params["ruleId"])
        assertNotEquals(imported.rules.map { it.id }, RuleCodec.prepareImport(bundle).rules.map { it.id })
    }
    @Test fun preparingInvalidBundlesDoesNotBypassValidation() {
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.prepareImport(RuleBundle(rules = listOf(target, target))) }
        val auto = target.copy(type = "auto", triggers = listOf(ComponentRegistry.newComponent("trigger.screen")))
        val caller = target.copy(id = "caller", actions = listOf(call("target")))
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.prepareImport(RuleBundle(rules = listOf(caller, auto))) }
    }
}
