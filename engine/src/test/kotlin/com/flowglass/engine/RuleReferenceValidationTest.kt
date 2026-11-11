package com.flowglass.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleReferenceValidationTest {
    private val longReference = "global.a" + ".a".repeat(1000)
    // Larger inputs keep the regression effective after JVM regex code has warmed up.
    private val stressReferences = listOf(".a", "[0]", ".a[000]").map { "global.a" + it.repeat(10_000) }
    private val count = Variable("count", "int", JsonPrimitive(0))

    private fun assignment(
        target: String,
        value: JsonElement = JsonPrimitive(0),
        locals: List<Variable> = emptyList()
    ) = Rule(
        id = "reference-rule", name = "Reference validation", type = "manual", locals = locals,
        actions = listOf(Component(id = "set", type = "action.variable_set", params = mapOf(
            "target" to JsonPrimitive(target), "value" to value
        )))
    )

    private fun input(rule: Rule): String = RuleCodec.encodeRule(rule).also {
        assertTrue("The import must fit within the existing JSON limit", it.toByteArray(Charsets.UTF_8).size < EngineLimits.MAX_BYTES)
    }

    @Test fun importAcceptsLongVariableTargetWithoutStackOverflow() {
        val rule = assignment(longReference)
        assertEquals(rule, RuleCodec.decodeBundle(input(rule)).rules.single())
    }

    @Test fun validationAcceptsLongVariableTargetWithoutStackOverflow() {
        assertTrue(RuleValidator.validate(assignment(longReference)).isEmpty())
    }

    @Test fun importRejectsLongNearMatchingTargetWithValidationIssue() {
        val error = assertThrows(RuleValidationException::class.java) {
            RuleCodec.decodeBundle(input(assignment("$longReference.")))
        }
        assertEquals(listOf("rules[0].actions[0].params.target"), error.issues.map { it.path })
    }

    @Test fun validationRejectsLongNearMatchingTargetWithValidationIssue() {
        assertEquals(listOf("actions[0].params.target"),
            RuleValidator.validate(assignment("$longReference.")).map { it.path })
    }

    @Test fun importRecognizesLongWholeVariableSubstitution() {
        val rule = assignment("local.count", JsonPrimitive("{$longReference}"), listOf(count))
        assertEquals(rule, RuleCodec.decodeBundle(input(rule)).rules.single())
    }

    @Test fun validationRecognizesLongWholeVariableSubstitution() {
        val rule = assignment("local.count", JsonPrimitive("{$longReference}"), listOf(count))
        assertTrue(RuleValidator.validate(rule).isEmpty())
    }

    @Test fun importRejectsLongNearMatchingSubstitutionAsWrongLocalType() {
        val rule = assignment("local.count", JsonPrimitive("{$longReference.}"), listOf(count))
        val error = assertThrows(RuleValidationException::class.java) { RuleCodec.decodeBundle(input(rule)) }
        assertEquals(listOf("rules[0].actions[0].params.value"), error.issues.map { it.path })
    }

    @Test fun validationRejectsLongNearMatchingSubstitutionAsWrongLocalType() {
        val rule = assignment("local.count", JsonPrimitive("{$longReference.}"), listOf(count))
        assertEquals(listOf("actions[0].params.value"), RuleValidator.validate(rule).map { it.path })
    }

    private fun assertAccepted(rule: Rule) {
        assertEquals(rule, RuleCodec.decodeBundle(input(rule)).rules.single())
        assertEquals(emptyList<ValidationIssue>(), RuleValidator.validate(rule))
    }

    private fun assertRejected(rule: Rule, path: String = "actions[0].params.target") {
        val error = assertThrows(RuleValidationException::class.java) { RuleCodec.decodeBundle(input(rule)) }
        assertEquals(listOf("rules[0].$path"), error.issues.map { it.path })
        assertEquals(listOf(path), RuleValidator.validate(rule).map { it.path })
    }

    @Test fun importAndValidationAcceptDeepDotIndexAndMixedPaths() {
        stressReferences.forEach { reference -> assertAccepted(assignment(reference)) }
    }

    @Test fun importAndValidationRejectDeepNearMatchingPaths() {
        stressReferences.forEach { reference ->
            listOf(".", "[", "[]", "[0", "[0]x", "..a", ".[0]", "[١]").forEach { suffix ->
                assertRejected(assignment(reference + suffix))
            }
        }
    }

    @Test fun importAndValidationRecognizeDeepWholeSubstitutions() {
        stressReferences.forEach { reference ->
            assertAccepted(assignment("local.count", JsonPrimitive("{$reference}"), listOf(count)))
        }
    }

    @Test fun importAndValidationRejectDeepNearMatchingSubstitutions() {
        stressReferences.forEach { reference ->
            listOf(".", "[", "[]", "[0", "[0]x", "..a", ".[0]", "[١]").forEach { suffix ->
                assertRejected(assignment("local.count", JsonPrimitive("{$reference$suffix}"), listOf(count)),
                    "actions[0].params.value")
            }
        }
    }

    @Test fun unicodeLettersAndAllNumberCategoriesRemainValidInIdentifiers() {
        // Include supplementary letters/digits, and Number categories Nd, Nl and No.
        listOf("_", "名", "é9", "x١Ⅷ²", "\uD801\uDC00\uD835\uDFCE").forEach { name ->
            val local = Variable(name, "int", JsonPrimitive(0))
            assertAccepted(assignment("local.$name", locals = listOf(local)))
            assertAccepted(assignment("global.$name.$name[000][12345678901234567890].$name"))
            assertAccepted(assignment("local.count", JsonPrimitive("{event.$name.$name[0]}"), listOf(count)))
        }
        listOf("1a", "١a", "Ⅷa", "²a", "e\u0301", "a-b", "a b", "😀").forEach { name ->
            assertRejected(assignment("global.$name"))
            assertRejected(assignment("global.a.$name"))
            assertRejected(assignment("local.count", JsonPrimitive("{global.$name}"), listOf(count)),
                "actions[0].params.value")
        }
    }

    @Test fun malformedScopesDotsAndBracketsRemainInvalidForTargetsAndSubstitutions() {
        listOf("global", "global.", "Global.a", "unknown.a", "global..a", "global.a.", "global.a..b",
            "global.a.[0]", "global.[0]", "global.a[]", "global.a[-1]", "global.a[+1]", "global.a[1.0]",
            "global.a[١]", "global.a[²]", "global.a[0", "global.a0]", "global.a[0]b", "global.a['b']",
            "global.a\n", " global.a", "global.a[ 0]", "global.a[0 ]").forEach { reference ->
            assertRejected(assignment(reference))
            assertRejected(assignment("local.count", JsonPrimitive("{$reference}"), listOf(count)),
                "actions[0].params.value")
        }
    }

    @Test fun scopePermissionsRemainTheSameForTargetsSourcesConditionsAndTriggers() {
        val locals = listOf(Variable("items", "list", JsonArray(emptyList())))
        listOf("local", "global", "event", "shell", "javascript", "list", "dict", "loop").forEach { scope ->
            val reference = "$scope.items[0].value"
            val rule = assignment(reference, locals = locals)
            if (scope in setOf("local", "global")) assertAccepted(rule) else assertRejected(rule)
            assertAccepted(rule.copy(actions = listOf(Component(type = "action.list_length",
                params = mapOf("source" to JsonPrimitive(reference))))))
            val auto = rule.copy(type = "auto", actions = listOf(Component(type = "action.stop")),
                triggers = listOf(ComponentRegistry.newComponent("trigger.screen")),
                conditions = listOf(Component(type = "condition.variable", params = mapOf(
                    "name" to JsonPrimitive(reference), "op" to JsonPrimitive("empty")))))
            assertAccepted(auto)
            val trigger = auto.copy(conditions = emptyList(), triggers = listOf(Component(
                type = "trigger.global_variable_changed", params = mapOf("name" to JsonPrimitive(reference)))))
            if (scope == "global") assertAccepted(trigger) else assertRejected(trigger, "triggers[0].params.name")
        }
    }

    @Test fun localDeclarationAndRootTypeChecksArePreserved() {
        assertRejected(assignment("local.missing.a"))
        assertRejected(assignment("local.count", JsonPrimitive("text"), listOf(count)), "actions[0].params.value")
        assertAccepted(assignment("local.count.a", JsonPrimitive("text"), listOf(count)))
        listOf("list", "dict").forEach { type ->
            val component = Component(type = if (type == "list") "action.list_append" else "action.dict_set",
                params = mapOf("target" to JsonPrimitive("local.items"), "value" to JsonPrimitive(0)) +
                    if (type == "dict") mapOf("key" to JsonPrimitive("key")) else emptyMap())
            val initial = if (type == "list") JsonArray(emptyList()) else JsonObject(emptyMap())
            val rule = assignment("global.unused").copy(actions = listOf(component),
                locals = listOf(Variable("items", type, initial)))
            assertAccepted(rule)
            assertRejected(rule.copy(locals = listOf(Variable("items", "int", JsonPrimitive(0)))))
            assertAccepted(rule.copy(actions = listOf(component.copy(params = component.params +
                ("target" to JsonPrimitive("local.items.nested")))), locals = listOf(Variable("items"))))
        }
    }

    @Test fun onlyWholeSyntacticallyValidReferencesBypassLocalValueTypeChecks() {
        listOf("local.undeclared", "global.a", "event.a", "shell.a", "javascript.a", "list.a", "dict.a", "loop.a").forEach {
            assertAccepted(assignment("local.count", JsonPrimitive("{$it}"), listOf(count)))
        }
        listOf("{}", "{{global.a}}", "prefix{global.a}", "{global.a}suffix", "{global.a} {global.b}",
            "{global.a", "global.a}", "{ global.a}", "{global.a }").forEach {
            assertRejected(assignment("local.count", JsonPrimitive(it), listOf(count)), "actions[0].params.value")
            assertAccepted(assignment("local.text", JsonPrimitive(it), listOf(Variable("text"))))
        }
    }
}
