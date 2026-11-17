package com.flowglass.engine

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariableSetActionTest {
    private fun rule(vararg actions: RuleComponent) = Rule(
        id = "rule-set",
        name = "变量",
        type = RuleType.MANUAL,
        enabled = true,
        actions = actions.toList(),
    )

    private class Sink : VariableSink {
        var ruleWrites: Map<String, Any?> = emptyMap()
        var globalWrites: Map<String, Any?> = emptyMap()
        var ruleDefinitions: List<VariableDefinition> = emptyList()
        var globalDefinitions: List<VariableDefinition> = emptyList()

        override fun persist(rule: Map<String, Any?>, global: Map<String, Any?>) {
            ruleWrites = rule
            globalWrites = global
        }

        override fun persistDefinitions(rule: List<VariableDefinition>, global: List<VariableDefinition>) {
            ruleDefinitions = rule
            globalDefinitions = global
        }
    }

    private fun executor(sink: Sink = Sink()) = RuleExecutor(
        ComponentRegistry().apply { BuiltInComponents.all.forEach(::register) },
        emptyMap(),
        CancellationCheckpoint { },
        sink = sink,
    )

    private fun set(
        name: String,
        value: String,
        type: String = "text",
        op: String = "set",
        scope: String = "local",
        retain: String = "false",
    ) = RuleComponent(
        "action.set_var",
        params = mapOf(
            "name" to JsonPrimitive(name),
            "value" to JsonPrimitive(value),
            "type" to JsonPrimitive(type),
            "op" to JsonPrimitive(op),
            "scope" to JsonPrimitive(scope),
            "retain" to JsonPrimitive(retain),
        ),
    )

    @Test
    fun newLocalIsCreatedEvenWhenTheRunValueIsNotRetained() = runSuspend {
        val sink = Sink()
        val log = executor(sink).run(rule(set("计数", "41", "number")), "测试")
        assertEquals(StepStatus.SUCCESS, log.status)
        assertTrue(sink.ruleWrites.isEmpty(), "retain=false must not write the run value")
        val definition = sink.ruleDefinitions.single()
        assertEquals("计数", definition.name)
        assertEquals(VariableScope.LOCAL, definition.scope)
        assertEquals(VariableType.NUMBER, definition.type)
        assertTrue(definition.resetEachRun)
        assertEquals(41L, definition.initial)
    }

    @Test
    fun retainedLocalWritesBackAndStartsFromStoredValue() = runSuspend {
        val sink = Sink()
        val doc = rule(set("计数", "1", "number", "add", retain = "true")).copy(
            locals = listOf(LocalVariable("计数", "number", JsonPrimitive(5), resetEachRun = false)),
        )
        val log = executor(sink).run(doc, "测试")
        assertEquals(StepStatus.SUCCESS, log.status)
        assertEquals(mapOf("计数" to 6L), sink.ruleWrites)
    }

    @Test
    fun nonRetainedLocalStaysAtItsInitialValueForTheNextRun() = runSuspend {
        val sink = Sink()
        val doc = rule(set("计数", "1", "number", "add")).copy(
            locals = listOf(LocalVariable("计数", "number", JsonPrimitive(5), resetEachRun = true)),
        )
        val log = executor(sink).run(doc, "测试")
        assertEquals(StepStatus.SUCCESS, log.status)
        assertTrue(sink.ruleWrites.isEmpty(), "resetEachRun=true must not write the run value")
    }

    @Test
    fun newGlobalCarriesItsDefinitionAndValue() = runSuspend {
        val sink = Sink()
        val log = executor(sink).run(rule(set("总数", "9", "number", scope = "global")), "测试")
        assertEquals(StepStatus.SUCCESS, log.status)
        assertEquals(mapOf("总数" to 9L), sink.globalWrites)
        assertEquals(VariableScope.GLOBAL, sink.globalDefinitions.single().scope)
    }

    @Test
    fun invalidNumberFailsTheStepWithAReason() = runSuspend {
        val log = executor().run(rule(set("计数", "不是数字", "number")), "测试")
        assertEquals(StepStatus.FAILURE, log.status)
        assertTrue(log.steps.single().detail.contains("不是数字"))
    }

    @Test
    fun writingABuiltinIsRejected() = runSuspend {
        val log = executor().run(rule(set("触发时间", "x")), "测试")
        assertEquals(StepStatus.FAILURE, log.status)
        assertTrue(log.steps.single().detail.contains("只读"))
    }

    private fun runSuspend(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
            result.exceptionOrNull()?.let { failure = it }
        })
        failure?.let { throw it }
    }
}
