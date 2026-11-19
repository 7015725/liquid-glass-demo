package com.flowglass.engine

import com.flowglass.engine.trigger.NetworkType
import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerDispatcher
import com.flowglass.engine.trigger.TriggerEnvironment
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerExecutor
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSkipRecorder
import com.flowglass.engine.trigger.TriggerSource
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriggerPolicyTest {
    private class Clock(var now: Long = 0) : com.flowglass.engine.Clock {
        override fun nowEpochMs(): Long = now
    }

    private class Env(
        var network: NetworkType = NetworkType.NONE,
        var charging: Boolean = false,
        var screenOn: Boolean = true,
        var battery: Int? = 80,
    ) : TriggerEnvironment {
        override fun zoneOffsetMinutes(atEpochMs: Long) = 0
        override fun batteryPercent() = battery
        override fun isCharging() = charging
        override fun isScreenOn() = screenOn
        override fun networkType() = network
    }

    private class Executor : TriggerExecutor {
        var active = 0
        var cancels = 0
        val calls = mutableListOf<String>()

        override fun isRunning(rule: TriggerRule): Boolean = active > 0
        override fun activeCount(rule: TriggerRule): Int = active
        override fun cancel(rule: TriggerRule) { cancels++ }

        override fun execute(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
            readOnlyNames: Set<String>,
        ): Boolean {
            calls += rule.id
            return true
        }
    }

    private fun trigger(type: String, vararg params: Pair<String, String>) = TriggerComponent(
        typeId = type,
        name = type.substringAfter('.'),
        params = params.associate { it.first to JsonPrimitive(it.second) },
    )

    private fun rule(
        id: String,
        triggers: List<TriggerComponent>,
        match: MatchSpec = MatchSpec(mode = MatchMode.ANY),
        run: RunMode = RunMode.DROP,
    ) = TriggerRule(
        id = id,
        cmdId = id.hashCode().toLong(),
        name = id,
        enabled = true,
        triggers = triggers,
        triggerMatch = match,
        runMode = run,
    )

    private fun dispatcher(clock: Clock, env: Env, executor: Executor) =
        TriggerDispatcher(clock, env, executor, TriggerSkipRecorder { _, _, _, _ -> })

    private fun screen(on: Boolean, at: Long = 0) = TriggerEvent(
        if (on) TriggerKinds.SCREEN_ON else TriggerKinds.SCREEN_OFF,
        TriggerSource.SCREEN,
        at,
    )

    @Test
    fun allModeNeedsEveryEventInsideTheWindow() {
        val clock = Clock(0)
        val executor = Executor()
        val d = dispatcher(clock, Env(), executor)
        val r = rule(
            "all-events",
            listOf(trigger("trigger.screen_on"), trigger("trigger.screen_off")),
            MatchSpec(mode = MatchMode.ALL, windowSeconds = 60),
        )

        assertEquals(0, d.dispatch(screen(true, 0), listOf(r)).executions.size)
        clock.now = 1_000
        assertEquals(1, d.dispatch(screen(false, 1_000), listOf(r)).executions.size)

        clock.now = 100_000
        assertEquals(0, d.dispatch(screen(true, 100_000), listOf(r)).executions.size)
        clock.now = 161_000
        assertEquals(0, d.dispatch(screen(false, 161_000), listOf(r)).executions.size)
    }

    @Test
    fun allModeReadsStateTriggersFromTheCurrentEnvironment() {
        val env = Env(network = NetworkType.WIFI, charging = true)
        val executor = Executor()
        val d = dispatcher(Clock(0), env, executor)
        val r = rule(
            "state-and-event",
            listOf(
                trigger("trigger.wifi", "state" to "connected"),
                trigger("trigger.charging", "state" to "charging"),
                trigger("trigger.screen_on"),
            ),
            MatchSpec(mode = MatchMode.ALL, windowSeconds = 60),
        )

        assertEquals(1, d.dispatch(screen(true, 0), listOf(r)).executions.size)
        env.charging = false
        assertEquals(0, d.dispatch(screen(true, 2_000), listOf(r)).executions.size)
    }

    @Test
    fun dropPolicyKeepsTheOldRunningGuard() {
        val executor = Executor().apply { active = 1 }
        val d = dispatcher(Clock(0), Env(), executor)
        val r = rule("drop", listOf(trigger("trigger.screen_on")), run = RunMode.DROP)

        val outcome = d.dispatch(screen(true, 0), listOf(r))

        assertEquals(0, outcome.executions.size)
        assertEquals(1, outcome.dropped)
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun restartPolicyCancelsTheOldRunBeforeStarting() {
        val executor = Executor().apply { active = 1 }
        val d = dispatcher(Clock(0), Env(), executor)
        val r = rule("restart", listOf(trigger("trigger.screen_on")), run = RunMode.RESTART)

        assertTrue(d.dispatch(screen(true, 0), listOf(r)).executions.isNotEmpty())
        assertEquals(1, executor.cancels)
        assertEquals(listOf("restart"), executor.calls)
    }

    @Test
    fun parallelPolicyStopsAtThreeActiveRuns() {
        val clock = Clock(0)
        val executor = Executor().apply { active = 2 }
        val d = dispatcher(clock, Env(), executor)
        val r = rule("parallel", listOf(trigger("trigger.screen_on")), run = RunMode.PARALLEL)

        assertTrue(d.dispatch(screen(true, 0), listOf(r)).executions.isNotEmpty())
        executor.active = 3
        clock.now = 3_000
        val full = d.dispatch(screen(true, 3_000), listOf(r))
        assertEquals(0, full.executions.size)
        assertEquals(1, full.dropped)
    }

    @Test
    fun queuePolicyIsBoundedAndStartsTheNextRunOnCompletion() {
        val clock = Clock(0)
        val executor = Executor().apply { active = 1 }
        val d = dispatcher(clock, Env(), executor)
        val r = rule("queue", listOf(trigger("trigger.screen_on")), run = RunMode.QUEUE)

        var queued = 0
        var dropped = 0
        repeat(12) { index ->
            clock.now = index * 3_000L
            val outcome = d.dispatch(screen(true, clock.now), listOf(r))
            queued += outcome.queued
            dropped += outcome.dropped
        }
        // Ten fit; two are dropped because the queue is bounded.
        assertEquals(10, queued)
        assertEquals(2, dropped)
        executor.active = 0
        d.onRunFinished(r)
        assertEquals(1, executor.calls.size)
    }
}
