package com.flowglass.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LimitsTest {
    private fun rule(actions: List<Component>) = Rule(name = "limit", type = "manual", actions = actions)
    private fun loop(child: Component) = Component(type = "action.loop", params = mapOf("count" to JsonPrimitive(1)), children = listOf(child))
    @Test fun componentCountAndDepthAreBoundedIncludingElseBranches() {
        assertTrue(RuleValidator.validate(rule(List(1000) { Component(type = "action.stop") })).isEmpty())
        assertTrue(RuleValidator.validate(rule(List(1001) { Component(type = "action.stop") })).isNotEmpty())
        var c = Component(type = "action.stop")
        repeat(31) { c = loop(c) }
        assertTrue(RuleValidator.validate(rule(listOf(c))).isEmpty())
        assertTrue(RuleValidator.validate(rule(listOf(loop(c)))).isNotEmpty())
    }
    @Test fun matchTreeDepthAndNodesAreBounded() {
        val trigger = ComponentRegistry.newComponent("trigger.screen")
        var tree = MatchNode(ref = trigger.id)
        repeat(32) { tree = MatchNode(op = "not", children = listOf(tree)) }
        val r = rule(listOf(Component(type = "action.stop"))).copy(type = "auto", triggers = listOf(trigger), triggerMatch = MatchSpec(mode = "custom", tree = tree))
        assertTrue(RuleValidator.validate(r).any { it.path.contains("tree") })
        assertTrue(RuleValidator.validate(r.copy(triggerMatch = MatchSpec(mode = "custom", tree = MatchNode(op = "and", children = List(1001) { MatchNode(ref = trigger.id) })))).isNotEmpty())
    }
    @Test fun variableCountAndJsonNestingAreBounded() {
        assertTrue(RuleValidator.validate(rule(listOf(Component(type = "action.stop"))).copy(locals = List(257) { Variable("v$it") })).isNotEmpty())
        var initial: JsonElement = JsonNull
        repeat(65) { initial = JsonArray(listOf(initial)) }
        assertTrue(RuleValidator.validateVariable(Variable("deep", "list", initial)).isNotEmpty())
        assertTrue(RuleValidator.validateVariable(Variable("large", initial = JsonPrimitive("x".repeat(1048577)))).isNotEmpty())
    }
}
