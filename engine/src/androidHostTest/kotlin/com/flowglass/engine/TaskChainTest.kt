package com.flowglass.engine

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Task-chain blocks: if / loop / wait / break / stop / variables / cross-rule calls. */
class TaskChainTest {
    private val registry = BuiltInComponents.registry()

    private fun meta(type: String) = ComponentMeta(
        typeId = type, name = type, category = "测试", kind = ComponentKind.ACTION,
        params = listOf(ParamSpec("text", "文本")),
    )

    private fun rule(vararg actions: RuleComponent) = Rule(
        id = "rule-1",
        name = "任务链",
        type = RuleType.MANUAL,
        enabled = true,
        actions = actions.toList(),
    )

    private fun ifBlock(
        conditions: List<RuleComponent>,
        children: List<RuleComponent>,
        branches: List<RuleBranch> = emptyList(),
    ) = RuleComponent("block.if", children = children, branches = branches, conditions = conditions)

    private fun loop(times: Int, children: List<RuleComponent>) = RuleComponent(
        "block.loop",
        params = mapOf("mode" to JsonPrimitive("times"), "times" to JsonPrimitive(times)),
        children = children,
    )

    private fun echo(log: MutableList<String>, value: String) = ComponentMeta(
        "a.$value", "echo", "测试", ComponentKind.ACTION,
        params = listOf(ParamSpec("text", "文本")),
    ) to Action { _, component ->
        log += component.type.removePrefix("a.")
        ActionOutcome(StepStatus.SUCCESS, value)
    }

    private fun condition(left: String, right: String) = RuleComponent(
        "condition.var_compare",
        params = mapOf("left" to JsonPrimitive(left), "op" to JsonPrimitive("eq"), "right" to JsonPrimitive(right)),
    )

    private fun executor(
        actions: Map<String, Action>,
        metas: List<ComponentMeta> = emptyList(),
        sink: VariableSink = VariableSink.Noop,
        checkpoint: CancellationCheckpoint = CancellationCheckpoint { },
        environment: ConditionEnvironment? = null,
        sleeper: suspend (Long) -> Unit = { Thread.sleep(it) },
        caller: RuleCaller? = null,
        maxSteps: Int = 10_000,
    ) = RuleExecutor(
        ComponentRegistry().apply {
            BuiltInComponents.all.forEach(::register)
            metas.forEach(::register)
        },
        actions, checkpoint,
        caller = caller, sink = sink, maxSteps = maxSteps, environment = environment, sleeper = sleeper,
    )

    @Test
    fun ifTakesTheThenBranchAndSkipsElse() = runSuspend {
        val log = mutableListOf<String>()
        val (thenMeta, thenAction) = echo(log, "then")
        val elseLog = mutableListOf<String>()
        val (elseMeta, elseAction) = echo(elseLog, "else")
        val block = ifBlock(
            conditions = listOf(condition("%{run.x}", "yes")),
            children = listOf(RuleComponent("a.then")),
            branches = listOf(RuleBranch(children = listOf(RuleComponent("a.else")))),
        )
        val result = executor(mapOf("a.then" to thenAction, "a.else" to elseAction), metas = listOf(thenMeta, elseMeta)).run(
            rule(block), "测试", mapOf("x" to "yes"),
        )
        assertEquals(StepStatus.SUCCESS, result.status)
        assertEquals(listOf("then"), log)
        assertTrue(elseLog.isEmpty())
        assertEquals(listOf(listOf(0, 0), listOf(0)), result.steps.map { it.path })
    }

    @Test
    fun ifFallsToElseWhenConditionFails() = runSuspend {
        val thenLog = mutableListOf<String>()
        val elseLog = mutableListOf<String>()
        val (thenMeta, thenAction) = echo(thenLog, "then")
        val (elseMeta, elseAction) = echo(elseLog, "else")
        val block = ifBlock(
            conditions = listOf(condition("%{run.x}", "yes")),
            children = listOf(RuleComponent("a.then")),
            branches = listOf(RuleBranch(children = listOf(RuleComponent("a.else")))),
        )
        val result = executor(mapOf("a.then" to thenAction, "a.else" to elseAction), metas = listOf(thenMeta, elseMeta)).run(
            rule(block), "测试", mapOf("x" to "no"),
        )
        assertEquals(StepStatus.SUCCESS, result.status)
        assertTrue(thenLog.isEmpty())
        assertEquals(listOf("else"), elseLog)
        assertEquals(listOf(listOf(0, -1, 0), listOf(0)), result.steps.map { it.path })
    }

    @Test
    fun loopRunsTheBodyThreeTimes() = runSuspend {
        val log = mutableListOf<String>()
        val (tickMeta, action) = echo(log, "tick")
        val result = executor(mapOf("a.tick" to action), metas = listOf(tickMeta))
            .run(rule(loop(3, listOf(RuleComponent("a.tick")))), "测试")
        assertEquals(3, log.size)
        assertEquals(StepStatus.SUCCESS, result.status)
        assertEquals("循环 3 次", result.steps.last().detail)
        assertTrue(result.steps.all { it.path.first() == 0 })
    }

    @Test
    fun breakLeavesTheLoopEarly() = runSuspend {
        val log = mutableListOf<String>()
        val (tickMeta, action) = echo(log, "tick")
        val body = listOf(RuleComponent("a.tick"), RuleComponent("block.break"))
        val result = executor(mapOf("a.tick" to action), metas = listOf(tickMeta)).run(rule(loop(5, body)), "测试")
        assertEquals(1, log.size)
        assertEquals(StepStatus.SUCCESS, result.status)
        assertTrue(result.steps.last().detail.contains("已跳出循环"))
    }

    @Test
    fun whileLoopStopsWhenTheConditionFails() = runSuspend {
        // run.x becomes "stop" on the first iteration: the second check ends the loop.
        val body = listOf(RuleComponent("action.set_var", params = mapOf(
            "name" to JsonPrimitive("x"), "op" to JsonPrimitive("set"), "value" to JsonPrimitive("%{run.y}"),
        )))
        val block = RuleComponent(
            "block.loop",
            params = mapOf("mode" to JsonPrimitive("while")),
            children = body,
            conditions = listOf(RuleComponent("condition.var_compare", params = mapOf(
                "left" to JsonPrimitive("%{run.x}"), "op" to JsonPrimitive("eq"), "right" to JsonPrimitive("go"),
            ))),
        )
        val result = executor(emptyMap())
            .run(rule(block), "测试", mapOf("x" to "go", "y" to "stop"))
        // First iteration runs, then x becomes "stop" and the loop ends.
        assertEquals("循环 1 次", result.steps.last().detail)
        assertEquals(StepStatus.SUCCESS, result.status)
    }

    @Test
    fun waitSucceedsAfterTheConditionTurnsTrue() = runSuspend {
        var charging = false
        var polls = 0
        var now = 0L
        val env = object : ConditionEnvironment {
            override fun zoneOffsetMinutes(atEpochMs: Long) = 0
            override fun batteryPercent() = 50
            override fun isCharging() = charging
            override fun isScreenOn() = true
        }
        val block = RuleComponent(
            "block.wait_until",
            params = mapOf("timeout" to JsonPrimitive(10_000), "poll" to JsonPrimitive(500)),
            conditions = listOf(RuleComponent("condition.charging", params = mapOf("state" to JsonPrimitive("charging")))),
        )
        val result = executor(
            emptyMap<String, Action>(),
            environment = env,
            sleeper = { ms -> polls++; now += ms; if (polls == 2) charging = true },
        ).run(rule(block), "测试")
        assertEquals(StepStatus.SUCCESS, result.status)
        assertEquals("条件已成立", result.steps.single().detail)
    }

    @Test
    fun waitTimesOutWithoutSpendingOneStepPerPoll() = runSuspend {
        var now = 0L
        val env = object : ConditionEnvironment {
            override fun zoneOffsetMinutes(atEpochMs: Long) = 0
            override fun batteryPercent() = 10
            override fun isCharging() = false
            override fun isScreenOn() = true
        }
        val block = RuleComponent(
            "block.wait_until",
            params = mapOf("timeout" to JsonPrimitive(2_000), "poll" to JsonPrimitive(250)),
            conditions = listOf(RuleComponent("condition.charging", params = mapOf("state" to JsonPrimitive("charging")))),
        )
        val result = executor(emptyMap<String, Action>(), environment = env, sleeper = { now += it })
            .run(rule(block), "测试")
        assertEquals(StepStatus.FAILURE, result.status)
        assertTrue(result.steps.single().detail.startsWith("等待超时"))
        // One block step + one skipped sibling: polling never consumes budget.
        assertEquals(1, result.steps.count { it.componentType == "block.wait_until" })
        assertFalse(result.detail.contains("步"))
    }

    @Test
    fun stepBudgetStopsLongRuns() = runSuspend {
        val action = Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "tick") }
        val result = executor(mapOf("a.tick" to action), metas = listOf(meta("a.tick")), maxSteps = 5)
            .run(rule(loop(100, listOf(RuleComponent("a.tick")))), "测试")
        assertEquals(StepStatus.FAILURE, result.status)
        assertTrue(result.detail.contains("超过单次运行 5 步"))
    }

    @Test
    fun loopCapIsOneThousandIterations() = runSuspend {
        val action = Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "tick") }
        val block = RuleComponent(
            "block.loop",
            params = mapOf("mode" to JsonPrimitive("while")),
            children = listOf(RuleComponent("a.tick")),
            conditions = listOf(condition("%{run.x}", "x")),
        )
        val result = executor(mapOf("a.tick" to action), metas = listOf(meta("a.tick")))
            .run(rule(block), "测试", mapOf("x" to "x"))
        assertEquals(StepStatus.SUCCESS, result.status)
        assertEquals("循环 1000 次", result.steps.last().detail)
    }

    @Test
    fun variablesAreWrittenBackOnlyForTouchedKeys() = runSuspend {
        var ruleWrites: Map<String, Any?>? = null
        var globalWrites: Map<String, Any?>? = null
        val sink = VariableSink { ruleMap, globalMap -> ruleWrites = ruleMap; globalWrites = globalMap }
        val actions = mapOf(
            "action.set_var" to Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "ok") },
        )
        val body = listOf(
            RuleComponent("action.set_var", params = mapOf(
                "name" to JsonPrimitive("rule.counter"), "op" to JsonPrimitive("add"), "value" to JsonPrimitive("2"),
            )),
            RuleComponent("action.set_var", params = mapOf(
                "name" to JsonPrimitive("global.total"), "op" to JsonPrimitive("set"), "value" to JsonPrimitive("9"),
            )),
        )
        val rule = rule(*body.toTypedArray()).copy(
            locals = listOf(LocalVariable("counter", "int", JsonPrimitive(3))),
        )
        val result = executor(actions, sink = sink).run(rule, "测试", globals = mapOf("total" to "1"))
        assertEquals(StepStatus.SUCCESS, result.status)
        assertEquals(mapOf("counter" to "5"), ruleWrites)
        assertEquals(mapOf("total" to "9"), globalWrites)
    }

    @Test
    fun stopBlockEndsTheRunWithItsStatus() = runSuspend {
        val action = Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "tick") }
        val stop = RuleComponent("block.stop", params = mapOf(
            "status" to JsonPrimitive("failure"), "message" to JsonPrimitive("提前收工"),
        ))
        val result = executor(mapOf("a.tick" to action), metas = listOf(meta("a.tick")))
            .run(rule(RuleComponent("a.tick"), stop, RuleComponent("a.tick")), "测试")
        assertEquals(StepStatus.FAILURE, result.status)
        assertEquals("提前收工", result.detail)
        assertEquals("提前收工", result.steps[1].detail)
    }

    @Test
    fun cancellationSkipsVariableWriteBack() {
        var written = false
        var checks = 0
        val rule = rule(RuleComponent("a.tick"))
        val executor = executor(
            mapOf("a.tick" to Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "ok") }),
            metas = listOf(meta("a.tick")),
            sink = VariableSink { _, _ -> written = true },
            checkpoint = CancellationCheckpoint {
                checks++
                if (checks > 2) throw kotlin.coroutines.cancellation.CancellationException("手动停止")
            },
        )
        val error = runCatching { runSuspend { executor.run(rule, "测试") } }.exceptionOrNull()
        assertTrue(error is kotlin.coroutines.cancellation.CancellationException)
        assertFalse(written)
    }

    @Test
    fun synchronousChildCallsShareTheBudgetAndDepth() = runSuspend {
        var depthSeen = -1
        val childActions = mapOf("a.tick" to Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "tick") })
        val caller = object : RuleCaller {
            override suspend fun call(ruleId: String, depth: Int, budget: RunBudget, variables: Map<String, Any?>): ActionOutcome {
                depthSeen = depth
                return ActionOutcome(StepStatus.SUCCESS, "子任务完成")
            }
            override fun launch(ruleId: String, depth: Int, variables: Map<String, Any?>): ActionOutcome =
                ActionOutcome(StepStatus.SUCCESS, "已发起")
        }
        val runChild = RuleComponent("action.run_rule", params = mapOf(
            "rule" to JsonPrimitive("子任务"), "wait" to JsonPrimitive("true"),
        ))
        val result = executor(childActions, caller = caller).run(rule(runChild), "测试")
        assertEquals(StepStatus.SUCCESS, result.status)
        assertEquals(1, depthSeen)
        assertEquals("子任务完成", result.steps.single().detail)
    }

    @Test
    fun asynchronousCallsAreLaunchedWithoutWaiting() = runSuspend {
        var launched: String? = null
        val caller = object : RuleCaller {
            override suspend fun call(ruleId: String, depth: Int, budget: RunBudget, variables: Map<String, Any?>): ActionOutcome =
                ActionOutcome(StepStatus.SUCCESS, "同步")
            override fun launch(ruleId: String, depth: Int, variables: Map<String, Any?>): ActionOutcome {
                launched = ruleId
                return ActionOutcome(StepStatus.SUCCESS, "已发起「$ruleId」")
            }
        }
        val runChild = RuleComponent("action.run_rule", params = mapOf(
            "rule" to JsonPrimitive("子任务"), "wait" to JsonPrimitive("false"),
        ))
        val result = executor(emptyMap(), caller = caller).run(rule(runChild), "测试")
        assertEquals("子任务", launched)
        assertEquals(StepStatus.SUCCESS, result.status)
        assertTrue(result.steps.single().detail.contains("已发起"))
    }

    @Test
    fun cyclesAndMissingTargetsAreRejectedAtSave() {
        fun auto(name: String, target: String) = Rule(
            id = name, name = name, type = RuleType.AUTO, enabled = true,
            actions = listOf(RuleComponent("action.run_rule", params = mapOf("rule" to JsonPrimitive(target)))),
        )
        val document = RuleDocument(rules = listOf(auto("甲", "乙"), auto("乙", "甲")))
        val issues = RuleValidator.validateDocument(document, registry)
        assertTrue(issues.any { it.message.contains("成环") }, issues.joinToString { it.message })

        val missing = RuleValidator.validateDocument(RuleDocument(rules = listOf(auto("甲", "丙"))), registry)
        assertTrue(missing.any { it.message.contains("不存在") })
    }

    @Test
    fun blockLevelRetryAndHomelessBreakAreRejected() {
        val retry = RuleComponent(
            "block.if", onError = OnError.RETRY,
            conditions = listOf(condition("%{run.x}", "x")),
            children = listOf(RuleComponent("a.tick")),
        )
        val breakOutside = RuleComponent("block.break")
        val issues = RuleValidator.validate(rule(retry, breakOutside), registry)
        assertTrue(issues.any { it.message.contains("块级错误处理") }, issues.joinToString { it.message })
        assertTrue(issues.any { it.message.contains("跳出循环") })
    }

    @Test
    fun deepCallsAreRejectedAtSave() {
        val chain = (1..7).map { index ->
            Rule(
                id = "r$index", name = "r$index", type = RuleType.AUTO, enabled = true,
                actions = listOf(RuleComponent("action.run_rule", params = mapOf(
                    "rule" to JsonPrimitive(if (index < 7) "r${index + 1}" else "end"),
                ))),
            )
        }
        val issues = RuleValidator.validateDocument(RuleDocument(rules = chain + Rule(
            id = "end", name = "end", type = RuleType.AUTO, enabled = true,
            actions = listOf(RuleComponent("action.delay", params = mapOf("duration" to JsonPrimitive(1000)))),
        )), registry)
        assertTrue(issues.any { it.message.contains("调用层级超过 5 层") }, issues.joinToString { it.message })
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }
}
