package com.flowglass.engine

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task flow v1: sequential steps, per-step onError, and a run context that carries
 * `%{var}` values from one step to the next.
 */
class TaskFlowTest {
    private fun meta(type: String, outputs: List<String> = emptyList()) = ComponentMeta(
        typeId = type,
        name = type,
        category = "测试",
        kind = ComponentKind.ACTION,
        params = listOf(ParamSpec("text", "文本")),
        outputs = outputs,
    )

    private fun rule(vararg actions: RuleComponent) = Rule(
        id = "rule-1",
        name = "任务",
        type = RuleType.MANUAL,
        enabled = true,
        actions = actions.toList(),
    )

    private fun executor(registry: ComponentRegistry, actions: Map<String, Action>) = RuleExecutor(
        registry,
        actions,
        CancellationCheckpoint { },
    )

    @Test
    fun continuePolicyRunsTheRestAndMarksTheRunFailed() = runSuspend {
        val registry = ComponentRegistry().apply { listOf(meta("a.fail"), meta("a.ok")).forEach(::register) }
        var ran = mutableListOf<String>()
        val executor = executor(registry, mapOf(
            "a.fail" to Action { _, _ -> ran += "fail"; ActionOutcome(StepStatus.FAILURE, "坏了") },
            "a.ok" to Action { _, _ -> ran += "ok"; ActionOutcome(StepStatus.SUCCESS, "好了") },
        ))

        val log = executor.run(rule(
            RuleComponent("a.fail", onError = OnError.CONTINUE),
            RuleComponent("a.ok"),
        ), "测试")

        assertEquals(listOf("fail", "ok"), ran)
        assertEquals(StepStatus.FAILURE, log.status)
        assertEquals(listOf(StepStatus.FAILURE, StepStatus.SUCCESS), log.steps.map { it.status })
    }

    @Test
    fun stopPolicyStillSkipsRemainingSteps() = runSuspend {
        val registry = ComponentRegistry().apply { listOf(meta("a.fail"), meta("a.ok")).forEach(::register) }
        var ran = mutableListOf<String>()
        val executor = executor(registry, mapOf(
            "a.fail" to Action { _, _ -> ran += "fail"; ActionOutcome(StepStatus.FAILURE, "坏了") },
            "a.ok" to Action { _, _ -> ran += "ok"; ActionOutcome(StepStatus.SUCCESS, "好了") },
        ))

        val log = executor.run(rule(
            RuleComponent("a.fail"),
            RuleComponent("a.ok"),
        ), "测试")

        assertEquals(listOf("fail"), ran)
        assertEquals(StepStatus.SKIPPED, log.steps[1].status)
    }

    @Test
    fun retryRunsTheSameStepTwiceBeforeStopping() = runSuspend {
        val registry = ComponentRegistry().apply { register(meta("a.flaky")) }
        var attempts = 0
        val executor = executor(registry, mapOf(
            "a.flaky" to Action { _, _ -> attempts++; ActionOutcome(StepStatus.FAILURE, "第 $attempts 次失败") },
        ))

        val log = executor.run(rule(RuleComponent("a.flaky", onError = OnError.RETRY)), "测试")

        assertEquals(2, attempts)
        assertEquals(1, log.steps.size)
        assertTrue(log.steps.single().detail.contains("第 2 次失败"))
    }

    @Test
    fun retrySucceedsOnTheSecondAttempt() = runSuspend {
        val registry = ComponentRegistry().apply { register(meta("a.flaky")) }
        var attempts = 0
        val executor = executor(registry, mapOf(
            "a.flaky" to Action { _, _ ->
                attempts++
                if (attempts == 1) ActionOutcome(StepStatus.FAILURE, "第一次失败")
                else ActionOutcome(StepStatus.SUCCESS, "第二次成功")
            },
        ))

        val log = executor.run(rule(RuleComponent("a.flaky", onError = OnError.RETRY)), "测试")

        assertEquals(2, attempts)
        assertEquals(StepStatus.SUCCESS, log.status)
    }

    @Test
    fun anOutputFeedsTheNextStepsParameters() = runSuspend {
        val registry = ComponentRegistry().apply {
            register(meta("a.produce", outputs = listOf("shell.stdout")))
            register(meta("a.consume"))
        }
        var seen: String? = null
        val executor = executor(registry, mapOf(
            "a.produce" to Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "完成", output = "hello") },
            "a.consume" to Action { _, component ->
                seen = component.params["text"]?.toString()
                ActionOutcome(StepStatus.SUCCESS, "收到")
            },
        ))

        val log = executor.run(rule(
            RuleComponent("a.produce"),
            RuleComponent("a.consume", params = mapOf("text" to JsonPrimitive("%{shell.stdout}"))),
        ), "测试")

        assertEquals(StepStatus.SUCCESS, log.status)
        assertEquals("\"hello\"", seen)
    }

    @Test
    fun thePreviousDetailIsAvailableAsLast() = runSuspend {
        val registry = ComponentRegistry().apply {
            register(meta("a.first"))
            register(meta("a.second"))
        }
        var seen: String? = null
        val executor = executor(registry, mapOf(
            "a.first" to Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "已连接公司 WiFi") },
            "a.second" to Action { _, component ->
                seen = component.params["text"]?.toString()
                ActionOutcome(StepStatus.SUCCESS, "好")
            },
        ))

        executor.run(rule(
            RuleComponent("a.first"),
            RuleComponent("a.second", params = mapOf("text" to JsonPrimitive("上一步：%{last}"))),
        ), "测试")

        assertEquals("\"上一步：已连接公司 WiFi\"", seen)
    }

    @Test
    fun unknownVariablesStayLiteral() = runSuspend {
        val registry = ComponentRegistry().apply { register(meta("a.echo")) }
        var seen: String? = null
        val executor = executor(registry, mapOf(
            "a.echo" to Action { _, component ->
                seen = component.params["text"]?.toString()
                ActionOutcome(StepStatus.SUCCESS, "好")
            },
        ))

        executor.run(rule(RuleComponent("a.echo", params = mapOf(
            "text" to JsonPrimitive("%{missing}"),
        ))), "测试")

        assertEquals("\"%{missing}\"", seen)
    }

    @Test
    fun expansionIsSinglePassSoAVariableCannotFeedItself() = runSuspend {
        val registry = ComponentRegistry().apply {
            register(meta("a.produce", outputs = listOf("v")))
            register(meta("a.echo"))
        }
        var seen: String? = null
        val executor = executor(registry, mapOf(
            "a.produce" to Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "完成", output = "%{v}") },
            "a.echo" to Action { _, component ->
                seen = component.params["text"]?.toString()
                ActionOutcome(StepStatus.SUCCESS, "好")
            },
        ))

        executor.run(rule(
            RuleComponent("a.produce"),
            RuleComponent("a.echo", params = mapOf("text" to JsonPrimitive("%{v}"))),
        ), "测试")

        assertEquals("\"%{v}\"", seen)
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
    fun skippedStepsAreNotFailures() = runSuspend {
        val registry = ComponentRegistry().apply { register(meta("a.ok")) }
        val executor = executor(registry, mapOf(
            "a.ok" to Action { _, _ -> ActionOutcome(StepStatus.SKIPPED, "暂不支持") },
        ))

        val log = executor.run(rule(RuleComponent("a.ok")), "测试")

        assertEquals(StepStatus.SKIPPED, log.status)
        assertFalse(log.steps.any { it.status == StepStatus.FAILURE })
    }
}
