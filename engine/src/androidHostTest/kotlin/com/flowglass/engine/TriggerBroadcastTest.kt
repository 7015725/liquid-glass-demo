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
import com.flowglass.engine.trigger.TriggerTypes
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Behaviour of the system-broadcast trigger family added in M1. */
class TriggerBroadcastTest {
    private class FakeClock(var now: Long = 0) : Clock {
        override fun nowEpochMs(): Long = now
    }

    private class FakeEnvironment : TriggerEnvironment {
        override fun zoneOffsetMinutes(atEpochMs: Long): Int = 0
        override fun batteryPercent(): Int? = null
        override fun isCharging(): Boolean = false
        override fun isScreenOn(): Boolean = true
        override fun isDndOn(): Boolean? = false
        override fun networkType(): NetworkType = NetworkType.NONE
        override fun wifiSsid(): String? = null
        override fun appProcessRunning(packageName: String, userId: Int): Boolean? = null
    }

    private class RecordingExecutor : TriggerExecutor {
        val calls = mutableListOf<Pair<TriggerRule, TriggerBinding>>()
        val variables = mutableListOf<Map<String, Any?>>()

        override fun isRunning(rule: TriggerRule): Boolean = false

        override fun execute(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
            readOnlyNames: Set<String>,
        ): Boolean {
            calls += rule to binding
            this.variables += variables
            return true
        }
    }

    private fun dispatcher(clock: FakeClock, executor: RecordingExecutor) = TriggerDispatcher(
        clock = clock,
        environment = FakeEnvironment(),
        executor = executor,
        skipRecorder = TriggerSkipRecorder { _, _, _, _ -> },
    )

    private fun trigger(typeId: String, vararg params: Pair<String, Any>) = TriggerComponent(
        typeId = typeId,
        name = typeId.substringAfter('.'),
        params = params.associate { (key, value) ->
            key to if (value is Int) JsonPrimitive(value) else JsonPrimitive(value.toString())
        },
    )

    private fun rule(id: String, triggers: List<TriggerComponent>) = TriggerRule(
        id = id,
        cmdId = id.hashCode().toLong(),
        name = id,
        enabled = true,
        triggers = triggers,
    )

    private fun event(
        kind: String,
        at: Long,
        vararg data: Pair<String, String>,
        source: TriggerSource = TriggerSource.BROADCAST,
    ) = TriggerEvent(kind, source, at, data.toMap())

    @Test
    fun systemTimeFamilyMatchesOnlyConfiguredKind() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("time", listOf(trigger(TriggerTypes.SYSTEM_TIME_CHANGED, "event" to "date")))
        assertEquals(0, d.dispatch(event(TriggerKinds.SYSTEM_TIME_CHANGED, 0, "event" to "time"), listOf(r)).executions.size)
        assertEquals(1, d.dispatch(event(TriggerKinds.SYSTEM_TIME_CHANGED, 3_000, "event" to "date"), listOf(r)).executions.size)
    }

    @Test
    fun storageMediaFamilyMatchesOnlyConfiguredEvent() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("media", listOf(trigger(TriggerTypes.STORAGE_MEDIA, "event" to "mounted")))
        assertEquals(0, d.dispatch(event(TriggerKinds.STORAGE_MEDIA, 0, "event" to "removed"), listOf(r)).executions.size)
        assertEquals(1, d.dispatch(event(TriggerKinds.STORAGE_MEDIA, 3_000, "event" to "mounted"), listOf(r)).executions.size)
    }

    @Test
    fun packageChangeFamilyAndOutputsStayAligned() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("pkg", listOf(trigger(TriggerTypes.PACKAGE_CHANGE, "event" to "added")))
        assertEquals(0, d.dispatch(event(TriggerKinds.PACKAGE_CHANGE, 0,
            "event" to "replaced", "package.name" to "com.example"), listOf(r)).executions.size)
        clock.now = 3_000
        val hit = d.dispatch(event(TriggerKinds.PACKAGE_CHANGE, 3_000,
            "event" to "added", "package.name" to "com.example", "package.label" to "示例"), listOf(r))
        assertEquals(1, hit.executions.size)
        val vars = executor.variables.single()
        assertEquals("com.example", vars["应用包名"])
        assertEquals("示例", vars["应用名称"])
        assertEquals("added", vars["应用事件"])
    }

    @Test
    fun batteryAnyChangeUsesThirtySecondCooldownAndIgnoresThreshold() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("any", listOf(trigger(TriggerTypes.BATTERY_LEVEL,
            "direction" to "any", "threshold" to "20")))
        fun battery(at: Long) = event(TriggerKinds.BATTERY, at, "battery" to "55", source = TriggerSource.BATTERY)

        assertEquals(1, d.dispatch(battery(0), listOf(r)).executions.size)
        clock.now = 10_000
        assertEquals(0, d.dispatch(battery(10_000), listOf(r)).executions.size)
        clock.now = 31_000
        assertEquals(1, d.dispatch(battery(31_000), listOf(r)).executions.size)
        assertEquals(2, executor.calls.size)
    }

    @Test
    fun batteryFullFiresOnRisingEdgeOnly() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("full", listOf(trigger(TriggerTypes.BATTERY_FULL)))
        fun full(at: Long, value: Boolean) = event(TriggerKinds.BATTERY_FULL, at,
            "battery" to "100", "full" to value.toString(), source = TriggerSource.BATTERY)

        assertEquals(0, d.dispatch(full(0, false), listOf(r)).executions.size)
        clock.now = 3_000
        assertEquals(1, d.dispatch(full(3_000, true), listOf(r)).executions.size)
        clock.now = 6_000
        assertEquals(0, d.dispatch(full(6_000, true), listOf(r)).executions.size)
        clock.now = 9_000
        assertEquals(0, d.dispatch(full(9_000, false), listOf(r)).executions.size)
        clock.now = 12_000
        assertEquals(1, d.dispatch(full(12_000, true), listOf(r)).executions.size)
    }

    @Test
    fun batteryOverheatFiresOnlyAboveConfiguredTemperature() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("hot", listOf(trigger(TriggerTypes.BATTERY_OVERHEAT, "temperature" to 45)))
        fun temp(at: Long, value: Int) = event(TriggerKinds.BATTERY_OVERHEAT, at,
            "battery.temperature" to value.toString(), source = TriggerSource.BATTERY)

        assertEquals(0, d.dispatch(temp(0, 40), listOf(r)).executions.size)
        clock.now = 3_000
        assertEquals(1, d.dispatch(temp(3_000, 46), listOf(r)).executions.size)
        clock.now = 6_000
        assertEquals(0, d.dispatch(temp(6_000, 47), listOf(r)).executions.size)
        clock.now = 9_000
        assertEquals(0, d.dispatch(temp(9_000, 40), listOf(r)).executions.size)
        clock.now = 12_000
        assertEquals(1, d.dispatch(temp(12_000, 46), listOf(r)).executions.size)
    }

    @Test
    fun storageLowFiresBelowThresholdAndRespectsCooldown() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("low", listOf(trigger(TriggerTypes.STORAGE_LOW, "threshold" to 10)))
        fun free(at: Long, value: Int) = event(TriggerKinds.STORAGE_LOW, at,
            "storage.freePercent" to value.toString())

        assertEquals(1, d.dispatch(free(0, 5), listOf(r)).executions.size)
        clock.now = 60_000
        assertEquals(0, d.dispatch(free(60_000, 5), listOf(r)).executions.size)
        clock.now = 1_800_000
        assertEquals(0, d.dispatch(free(1_800_000, 20), listOf(r)).executions.size)
        clock.now = 1_803_000
        assertEquals(1, d.dispatch(free(1_803_000, 5), listOf(r)).executions.size)
    }

    @Test
    fun settingChangeNeedsExactNamespaceAndKey() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("setting", listOf(trigger(TriggerTypes.SETTING_CHANGED,
            "namespace" to "system", "key" to "screen_brightness")))
        assertEquals(0, d.dispatch(event(TriggerKinds.SETTING_CHANGED, 0,
            "setting.namespace" to "secure", "setting.key" to "screen_brightness",
            "setting.value" to "10"), listOf(r)).executions.size)
        assertEquals(1, d.dispatch(event(TriggerKinds.SETTING_CHANGED, 3_000,
            "setting.namespace" to "system", "setting.key" to "screen_brightness",
            "setting.value" to "80"), listOf(r)).executions.size)
    }

    @Test
    fun tickEventsOnlyFireTheirTargetBinding() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val a = rule("a", listOf(trigger(TriggerTypes.TICK, "interval" to "30")))
        val b = rule("b", listOf(trigger(TriggerTypes.TICK, "interval" to "30")))
        val forA = TriggerEvent(TriggerKinds.TICK, TriggerSource.BROADCAST, 0,
            mapOf("tick.interval" to "30"), targetRuleId = "a", targetTriggerIndex = 0)
        assertEquals(1, d.dispatch(forA, listOf(a, b)).executions.size)
        assertEquals("a", executor.calls.single().first.id)

        clock.now = 3_000
        val forB = TriggerEvent(TriggerKinds.TICK, TriggerSource.BROADCAST, 3_000,
            mapOf("tick.interval" to "30"), targetRuleId = "b", targetTriggerIndex = 0)
        assertEquals(1, d.dispatch(forB, listOf(a, b)).executions.size)
        assertEquals("b", executor.calls.last().first.id)
    }

    @Test
    fun everyTriggerDeclaresAListenerFamily() {
        val missing = BuiltInComponents.triggers
            .filter { TriggerTypes.sourceOf(it.typeId) == null }
            .map { it.typeId }
        assertTrue(missing.isEmpty(), "缺少事件源：$missing")
    }
}
