package com.flowglass.engine

import com.flowglass.engine.log.TraceEvent
import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceSink
import com.flowglass.engine.log.TraceStatus
import com.flowglass.engine.log.TraceRedaction
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceCoreTest {
    private class RecordingTraceSink : TraceSink {
        val events = mutableListOf<TraceEvent>()
        override fun record(event: TraceEvent) { events += event }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return checkNotNull(result) { "测试协程发生了非同步挂起" }.getOrThrow()
    }

    @Test
    fun actionEmitsStructuredTraceWithoutPerComponentLogging() {
        val registry = ComponentRegistry()
        registry.register(
            ComponentMeta(
                typeId = "action.echo",
                name = "回显",
                category = "测试",
                kind = ComponentKind.ACTION,
                params = listOf(ParamSpec("text", "文本", ParamType.TEXT, required = true)),
                supports = listOf(LayerSupport(ExecutionLayer.NORMAL)),
            ),
        )
        val sink = RecordingTraceSink()
        val executor = RuleExecutor(
            registry = registry,
            actions = mapOf(
                "action.echo" to Action { _, component ->
                    ActionOutcome(
                        StepStatus.SUCCESS,
                        "完成",
                        outputs = mapOf("echo.value" to component.params["text"]?.toString().orEmpty()),
                    )
                },
            ),
            cancellationCheckpoint = CancellationCheckpoint { },
            trace = sink,
        )
        val rule = RuleFactory.create(RuleType.MANUAL, "日志测试").copy(
            actions = listOf(RuleComponent("action.echo", mapOf("text" to JsonPrimitive("hello")))),
        )

        runSuspend { executor.run(rule, "手动执行") }

        val action = sink.events.single {
            it.kind == TraceKind.ACTION && it.status == TraceStatus.SUCCESS
        }
        assertEquals("action.echo", action.componentTypeId)
        assertEquals("hello", action.params["text"])
        assertTrue(action.componentPath.isNotEmpty())
        assertTrue(action.runId != null)
    }

    @Test
    fun blockConditionEmitsPerConditionTrace() {
        val registry = ComponentRegistry()
        registry.register(
            ComponentMeta(
                typeId = "block.if",
                name = "如果",
                category = "流程",
                kind = ComponentKind.ACTION,
                supports = listOf(LayerSupport(ExecutionLayer.NORMAL)),
                block = BlockKind.IF,
            ),
        )
        val sink = RecordingTraceSink()
        val executor = RuleExecutor(
            registry = registry,
            actions = emptyMap(),
            cancellationCheckpoint = CancellationCheckpoint { },
            trace = sink,
        )
        val rule = RuleFactory.create(RuleType.MANUAL, "条件测试").copy(
            actions = listOf(
                RuleComponent(
                    "block.if",
                    conditions = listOf(
                        RuleComponent("condition.charging", mapOf("state" to JsonPrimitive("charging"))),
                    ),
                    children = listOf(RuleComponent("action.never")),
                ),
            ),
        )

        runSuspend { executor.run(rule, "手动执行") }

        val conditions = sink.events.filter { it.kind == TraceKind.CONDITION }
        assertTrue(conditions.isNotEmpty())
        assertEquals("condition.charging", conditions.first().componentTypeId)
        assertTrue(conditions.all { it.status == TraceStatus.FAILURE })
    }

    @Test
    fun runTraceUsesTheRunLogIdAsCorrelationId() {
        val sink = RecordingTraceSink()
        val executor = RuleExecutor(
            registry = ComponentRegistry(),
            actions = emptyMap(),
            cancellationCheckpoint = CancellationCheckpoint { },
            trace = sink,
        )
        val log = runSuspend { executor.run(RuleFactory.create(RuleType.MANUAL, "空规则"), "手动执行") }

        val started = sink.events.first { it.kind == TraceKind.RUN && it.status == TraceStatus.STARTED }
        val finished = sink.events.first { it.kind == TraceKind.RUN && it.status == TraceStatus.SKIPPED }
        assertEquals(log.id, started.runId)
        assertEquals(log.id, finished.runId)
    }

    @Test
    fun redactionMasksSecretsAndTruncatesLongValues() {
        val meta = ComponentMeta(
            typeId = "action.test",
            name = "测试",
            category = "测试",
            kind = ComponentKind.ACTION,
            params = listOf(
                ParamSpec("password", "密码", ParamType.TEXT),
                ParamSpec("text", "文本", ParamType.TEXT),
            ),
            supports = listOf(LayerSupport(ExecutionLayer.NORMAL)),
        )
        val redacted = TraceRedaction.params(
            meta,
            mapOf(
                "password" to JsonPrimitive("p@ss"),
                "text" to JsonPrimitive("x".repeat(600)),
                "authorization" to JsonPrimitive("Bearer abc"),
            ),
        )

        assertEquals("***", redacted["password"])
        assertEquals("***", redacted["authorization"])
        assertTrue(redacted.getValue("text").endsWith("…"))
        assertEquals(513, redacted.getValue("text").length)
        assertEquals(
            "***",
            TraceRedaction.event(mapOf("body" to "通知正文"), includeRaw = false).getValue("body"),
        )
    }
}
