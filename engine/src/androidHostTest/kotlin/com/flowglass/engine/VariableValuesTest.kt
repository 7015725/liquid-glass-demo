package com.flowglass.engine

import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VariableValuesTest {
    @Test
    fun sixTypesUseChineseLabelsAndStableStorageKeys() {
        assertEquals(
            listOf("int", "boolean", "text", "int_list", "boolean_list", "text_list"),
            VariableType.values().map { it.storageKey },
        )
        assertEquals(VariableType.INT, VariableType.fromStorage("int"))
        assertEquals(VariableType.TEXT_LIST, VariableType.fromStorage("text_list"))
        assertEquals(null, VariableType.fromStorage("number"))
        assertEquals(null, VariableType.fromStorage("list"))
    }

    @Test
    fun integersRejectNonIntegersAndDivisionByZeroIsReported() {
        assertEquals(42L, VariableValues.parseInt("42"))
        assertTrue(assertFailsWith<IllegalArgumentException> { VariableValues.parseInt("4.2") }
            .message.orEmpty().contains("『4.2』不是整数"))

        val rule = Rule(
            id = "r", type = RuleType.MANUAL,
            locals = listOf(LocalVariable("计数", "int", kotlinx.serialization.json.JsonPrimitive(5))),
            actions = listOf(
                RuleComponent("action.set_var", params = mapOf(
                    "name" to kotlinx.serialization.json.JsonPrimitive("计数"),
                    "type" to kotlinx.serialization.json.JsonPrimitive("int"),
                    "op" to kotlinx.serialization.json.JsonPrimitive("add"),
                    "value" to kotlinx.serialization.json.JsonPrimitive("5"),
                    "scope" to kotlinx.serialization.json.JsonPrimitive("local"),
                    "retain" to kotlinx.serialization.json.JsonPrimitive("false"),
                )),
            ),
        )
        val log = runBlockingEngine(rule)
        assertEquals(StepStatus.SUCCESS, log.status)
        assertEquals("计数 = 10", log.steps.single().detail)

        val divide = rule.copy(actions = listOf(
            rule.actions.single().copy(params = rule.actions.single().params +
                ("op" to kotlinx.serialization.json.JsonPrimitive("div")) +
                ("value" to kotlinx.serialization.json.JsonPrimitive("0"))),
        ))
        val divideLog = runBlockingEngine(divide)
        assertEquals(StepStatus.FAILURE, divideLog.status)
        assertTrue(divideLog.steps.single().detail.contains("不能除以 0"))
    }

    @Test
    fun booleansAcceptTheDocumentedWordsAndStoreBooleans() {
        for (word in listOf("true", "真", "1", "是")) assertEquals(true, VariableValues.parseBoolean(word))
        for (word in listOf("false", "假", "0", "否")) assertEquals(false, VariableValues.parseBoolean(word))
        assertFailsWith<IllegalArgumentException> { VariableValues.parseBoolean("2") }
        assertEquals("真", VariableValues.uiText(true))
        assertEquals("假", VariableValues.uiText(false))
    }

    @Test
    fun listsAcceptCommasNewlinesAndBracketsAndExpandAsCommaText() {
        assertEquals(listOf(1L, 2L, 3L), VariableValues.parse("1,2\n3", VariableType.INT_LIST))
        assertEquals(listOf(1L, 2L, 3L), VariableValues.parse("[1,2,3]", VariableType.INT_LIST))
        assertEquals(listOf("甲", "乙"), VariableValues.parse("甲,乙", VariableType.TEXT_LIST))
        assertEquals(listOf(true, false), VariableValues.parse("真,假", VariableType.BOOLEAN_LIST))
        assertFailsWith<IllegalArgumentException> {
            VariableValues.parse("1,不是整数", VariableType.INT_LIST)
        }
        assertEquals("1,2,3", VariableValues.text(listOf(1L, 2L, 3L)))
        assertEquals("x=甲,乙", ParamTemplate.expand("x={列表}", mapOf("列表" to listOf("甲", "乙"))))
    }

    private fun runBlockingEngine(rule: Rule): RunLog {
        var result: RunLog? = null
        runSuspend {
            result = RuleExecutor(
                ComponentRegistry().apply { BuiltInComponents.all.forEach(::register) },
                emptyMap(),
                CancellationCheckpoint { },
            ).run(rule, "测试")
        }
        return requireNotNull(result)
    }

    private fun runSuspend(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(
            kotlin.coroutines.Continuation(kotlin.coroutines.EmptyCoroutineContext) { outcome ->
                failure = outcome.exceptionOrNull()
            },
        )
        failure?.let { throw it }
    }
}
