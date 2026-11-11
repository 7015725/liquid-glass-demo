package com.flowglass.engine

import org.junit.Assert.*
import org.junit.Test

class RuleValidatorTest {
    private fun manual() = Rule(name = "测试", type = "manual", actions = listOf(Component(type = "action.stop")))
    private fun issue(rule: Rule, path: String) = assertTrue("Expected $path: ${RuleValidator.validate(rule)}", RuleValidator.validate(rule).any { it.path == path })
    @Test fun ruleEnvelopeAndManualAutoSemanticsAreValidated() {
        assertTrue(RuleValidator.validate(manual()).isEmpty())
        issue(manual().copy(id = ""), "id")
        issue(manual().copy(schemaVersion = 2), "schemaVersion")
        issue(manual().copy(type = "other"), "type")
        issue(manual().copy(runMode = "typo"), "runMode")
        issue(manual().copy(debounceMillis = -1), "debounceMillis")
        issue(manual().copy(actions = emptyList()), "actions")
        issue(manual().copy(type = "auto"), "triggers")
        issue(manual().copy(triggers = listOf(Component(type = "trigger.screen"))), "triggers")
        issue(manual().copy(conditions = listOf(Component(type = "condition.charging"))), "conditions")
    }
    @Test fun localsRejectDuplicatesAndInvalidInitialValues() {
        issue(manual().copy(locals = listOf(Variable("a"), Variable("a"))), "locals[1].name")
        issue(manual().copy(locals = listOf(Variable("a", "int"))), "locals[0].initial")
    }
    @Test fun componentIdsKindsAndFailurePoliciesAreCheckedRecursively() {
        val stop = Component(id = "same", type = "action.stop")
        issue(manual().copy(actions = listOf(stop, stop)), "actions[1].id")
        issue(manual().copy(actions = listOf(stop.copy(id = ""))), "actions[0].id")
        issue(manual().copy(actions = listOf(stop.copy(type = "action.unknown"))), "actions[0].type")
        issue(manual().copy(actions = listOf(stop.copy(type = "trigger.screen"))), "actions[0].type")
        issue(manual().copy(actions = listOf(stop.copy(timeoutSeconds = 0))), "actions[0].timeoutSeconds")
        issue(manual().copy(actions = listOf(stop.copy(onError = "unknown"))), "actions[0].onError")
        issue(manual().copy(actions = listOf(stop.copy(onError = "retry", retryCount = 0))), "actions[0].retryCount")
        issue(manual().copy(actions = listOf(stop.copy(retryCount = -1))), "actions[0].retryCount")
        issue(manual().copy(actions = listOf(stop.copy(children = listOf(Component(type = "action.stop"))))), "actions[0].children")
        issue(manual().copy(actions = listOf(Component(type = "action.loop", elseActions = listOf(stop)))), "actions[0].elseActions")
        issue(manual().copy(actions = listOf(Component(type = "action.if", children = listOf(stop), elseActions = listOf(stop)))), "actions[0].elseActions[0].id")
    }
    @Test fun blankRuleNameIsRejected() {
        assertTrue(RuleValidator.validate(Rule()).any { it.path == "name" })
    }
}
