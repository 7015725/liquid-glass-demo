package com.flowglass.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptWarningsTest {
    @Test fun warningsContainEveryFullScriptIncludingDeepElseAndExpressions() {
        val shell = "printf \"hello\"\n" + "# 注释\n".repeat(5000) + "# END-shell"
        val js = "const greeting = '你好';\nctx.value = 1; // END-js"
        val expression = "ctx.global.mode === 'auto' /* END-if */"
        val condition = "ctx.battery < 20 /* END-condition */"
        val calculated = "ctx.local.n + 1 /* END-calculate */"
        val embedded = "2 + 2 /* END-embedded */"
        val actions = listOf(Component(type = "action.if", params = mapOf("expression" to JsonPrimitive(expression)), children = listOf(
            Component(type = "action.loop", params = mapOf("count" to JsonPrimitive(2)), children = listOf(Component(type = "action.shell", params = mapOf("cmd" to JsonPrimitive(shell), "root" to JsonPrimitive(true)))))
        ), elseActions = listOf(Component(type = "action.javascript", params = mapOf("code" to JsonPrimitive(js))), Component(type = "action.variable_calculate", params = mapOf("target" to JsonPrimitive("local.n"), "expression" to JsonPrimitive(calculated))), Component(type = "action.variable_set", params = mapOf("target" to JsonPrimitive("global.cfg"), "value" to JsonObject(mapOf("nested" to JsonArray(listOf(JsonObject(mapOf("expression" to JsonPrimitive(embedded))))))))))))
        val rule = Rule(name = "script", triggers = listOf(ComponentRegistry.newComponent("trigger.screen")), conditions = listOf(Component(type = "condition.expression", params = mapOf("expression" to JsonPrimitive(condition)))), locals = listOf(Variable("n", "int", JsonPrimitive(0))), actions = actions)
        val warnings = RuleCodec.scriptWarnings(RuleBundle(rules = listOf(rule)))
        assertEquals(6, warnings.size)
        listOf(shell, js, expression, condition, calculated, embedded).forEach { code -> assertTrue(code.takeLast(30), warnings.any { it.endsWith(code) }) }
        assertTrue(warnings.any { it.contains("root=true") })
        assertTrue(warnings.any { it.contains("elseActions") })
        assertTrue(warnings.any { it.contains("conditions[0]") })
    }
    @Test fun identicalScriptsHaveSeparateWarningsAndNoScriptMeansNoWarning() {
        val c = Component(type = "action.shell", params = mapOf("cmd" to JsonPrimitive("id")))
        val r = Rule(name = "x", type = "manual", actions = listOf(c, c.copy(id = "another")))
        assertEquals(2, RuleCodec.scriptWarnings(RuleBundle(rules = listOf(r))).size)
        assertTrue(RuleCodec.scriptWarnings(RuleBundle(rules = listOf(r.copy(actions = listOf(Component(type = "action.stop")))))).isEmpty())
    }
}
