package com.flowglass.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ParameterValidationTest {
    private fun issues(type: String, params: Map<String, JsonElement>, locals: List<Variable> = emptyList()): List<ValidationIssue> {
        val c = Component(type = type, params = params)
        val r = if (type.startsWith("condition.")) Rule(name = "test", triggers = listOf(ComponentRegistry.newComponent("trigger.screen")), conditions = listOf(c), actions = listOf(Component(type = "action.stop")), locals = locals)
            else Rule(name = "test", type = "manual", actions = listOf(c), locals = locals)
        return RuleValidator.validate(r)
    }
    private fun invalid(type: String, key: String, value: JsonElement) {
        val params = ComponentRegistry.newComponent(type).params + (key to value)
        assertTrue("$type $key=$value", issues(type, params).any { it.path.endsWith(".params.$key") })
    }
    @Test fun requiredAndChoiceValuesAreNotSilentlyDefaulted() {
        assertTrue(issues("action.shell", emptyMap()).any { it.path == "actions[0].params.cmd" })
        invalid("action.shell", "cmd", JsonPrimitive("  "))
        invalid("action.set_ringer", "mode", JsonPrimitive("normla"))
        invalid("action.shell", "root", JsonPrimitive("true"))
        invalid("action.shell", "backend", JsonPrimitive("adb"))
        assertTrue(issues("action.shell", mapOf("cmd" to JsonPrimitive("id"), "root" to JsonPrimitive(true))).isEmpty())
        assertTrue(issues("action.stop", mapOf("unexpected" to JsonPrimitive(1))).any { it.path.endsWith("params.unexpected") })
    }
    @Test fun integerParametersRejectOverflowFractionAndString() {
        listOf(JsonPrimitive("1"), JsonPrimitive(1.2), Json.parseToJsonElement("9223372036854775808"), JsonPrimitive(0), JsonPrimitive(-1)).forEach { invalid("action.loop", "count", it) }
        invalid("action.delay", "millis", JsonPrimitive(-1))
        assertTrue(issues("action.loop", mapOf("count" to JsonPrimitive(Long.MAX_VALUE))).isEmpty())
    }
    @Test fun semanticTimeAndThresholdConstraintsAreChecked() {
        invalid("condition.time_range", "from", JsonPrimitive("24:00"))
        invalid("condition.time_range", "to", JsonPrimitive("8:5"))
        invalid("condition.battery", "level", JsonPrimitive(101))
        invalid("condition.battery", "level", JsonPrimitive(-1))
        invalid("action.wait_event", "eventType", JsonPrimitive("action.stop"))
        assertTrue(issues("condition.time_range", mapOf("from" to JsonPrimitive("23:00"), "to" to JsonPrimitive("06:00"))).isEmpty())
    }
    @Test fun invalidTemplateTextDoesNotBypassAssignedVariableTypeChecks() {
        val locals = listOf(Variable("count", "int", JsonPrimitive(0)))
        val params = mapOf("target" to JsonPrimitive("local.count"), "value" to JsonPrimitive("{broken"))
        assertTrue(issues("action.variable_set", params, locals).any { it.path.endsWith("params.value") })
        assertTrue(issues("action.variable_set", params + ("value" to JsonPrimitive("{event.battery}")), locals).isEmpty())
    }
    @Test fun variableTargetsRespectScopeAndCollectionTypes() {
        val params = mapOf("target" to JsonPrimitive("local.items"), "value" to JsonPrimitive(1))
        assertTrue(issues("action.list_append", params).any { it.path.endsWith("params.target") })
        assertTrue(issues("action.list_append", params, listOf(Variable("items"))).any { it.path.endsWith("params.target") })
        assertTrue(issues("action.list_append", params, listOf(Variable("items", "list", JsonArray(emptyList())))).isEmpty())
        invalid("action.variable_delete", "target", JsonPrimitive("event.title"))
        invalid("action.variable_delete", "target", JsonPrimitive("shell.stdout"))
        invalid("action.variable_set", "target", JsonPrimitive("other.local.x"))
        assertTrue(issues("action.variable_set", mapOf("target" to JsonPrimitive("global.created"), "value" to JsonNull)).isEmpty())
    }
}
