package com.flowglass.engine

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BundleValidationTest {
    private val rule = Rule(id = "r", name = "test", type = "manual", actions = listOf(Component(type = "action.stop")))
    private fun reject(bundle: RuleBundle) {
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeBundle(RuleCodec.json.encodeToString(bundle)) }
    }
    @Test fun unknownVersionsAndComponentTypesAreNotAccepted() {
        reject(RuleBundle(formatVersion = 2, rules = listOf(rule)))
        reject(RuleBundle(rules = listOf(rule.copy(schemaVersion = 2))))
        reject(RuleBundle(rules = listOf(rule.copy(actions = listOf(Component(type = "action.future"))))))
        reject(RuleBundle(rules = listOf(rule.copy(name = ""))))
    }
    @Test fun duplicateRuleIdsAndGlobalNamesRejectWholeBundle() {
        reject(RuleBundle(rules = listOf(rule, rule)))
        reject(RuleBundle(rules = listOf(rule), globals = listOf(Variable("mode"), Variable("mode"))))
        reject(RuleBundle(rules = listOf(rule), globals = listOf(Variable("n", "int"))))
        reject(RuleBundle(rules = List(101) { rule.copy(id = "r$it") }))
        reject(RuleBundle(rules = emptyList(), globals = List(257) { Variable("v$it") }))
        assertEquals(emptyList<Rule>(), RuleCodec.decodeBundle("{\"formatVersion\":1,\"rules\":[],\"globals\":[]}").rules)
    }
    @Test fun invalidContentCannotBeExportedAsValidatedBundle() {
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.exportBundle(listOf(rule.copy(actions = emptyList()))) }
    }
}
