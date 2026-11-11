package com.flowglass.engine

import org.junit.Assert.*
import org.junit.Test

class MatchValidationTest {
    private val a = ComponentRegistry.newComponent("trigger.screen").copy(id = "a")
    private val b = ComponentRegistry.newComponent("trigger.charging").copy(id = "b")
    private val base = Rule(name = "match", triggers = listOf(a, b), actions = listOf(Component(type = "action.stop")))
    private fun invalid(spec: MatchSpec) = assertTrue(spec.toString(), RuleValidator.validate(base.copy(triggerMatch = spec)).any { it.path.startsWith("triggerMatch") })
    @Test fun customRequiresExactlyOneTreeOrValidThreshold() {
        invalid(MatchSpec(mode = "bad"))
        invalid(MatchSpec(windowSeconds = 0))
        invalid(MatchSpec(mode = "custom"))
        invalid(MatchSpec(mode = "custom", atLeast = 1, tree = MatchNode(ref = "a")))
        invalid(MatchSpec(mode = "custom", atLeast = 0))
        invalid(MatchSpec(mode = "custom", atLeast = 3))
        invalid(MatchSpec(mode = "any", tree = MatchNode(ref = "a")))
        assertTrue(RuleValidator.validate(base.copy(triggerMatch = MatchSpec(mode = "custom", atLeast = 2))).isEmpty())
    }
    @Test fun malformedTreesReportPathsInsteadOfBeingIgnored() {
        listOf(
            MatchNode(ref = "missing"), MatchNode(ref = "0"), MatchNode(),
            MatchNode(ref = "a", children = listOf(MatchNode(ref = "b"))),
            MatchNode(op = "xor"), MatchNode(op = "and"),
            MatchNode(op = "or", ref = "a", children = listOf(MatchNode(ref = "b"))),
            MatchNode(op = "not"), MatchNode(op = "not", children = listOf(MatchNode(ref = "a"), MatchNode(ref = "b")))
        ).forEach { invalid(MatchSpec(mode = "custom", tree = it)) }
        val foreign = base.copy(conditionMatch = MatchSpec(mode = "custom", tree = MatchNode(ref = "a")))
        assertTrue(RuleValidator.validate(foreign).any { it.path.startsWith("conditionMatch.tree") })
    }
    @Test fun idReferencesSurviveReorderingButDeletionIsRejected() {
        val tree = MatchNode(op = "and", children = listOf(MatchNode(ref = "a"), MatchNode(op = "not", children = listOf(MatchNode(ref = "b")))))
        val rule = base.copy(triggerMatch = MatchSpec(mode = "custom", tree = tree))
        assertTrue(RuleValidator.validate(rule).isEmpty())
        val sorted = rule.copy(triggers = rule.triggers.reversed())
        assertTrue(RuleValidator.validate(sorted).isEmpty())
        assertEquals(tree, sorted.triggerMatch.tree)
        assertTrue(RuleValidator.validate(rule.copy(triggers = listOf(a))).any { it.path.endsWith(".ref") })
    }
}
