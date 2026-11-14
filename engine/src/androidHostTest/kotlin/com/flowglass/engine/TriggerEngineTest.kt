package com.flowglass.engine

import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerDispatcher
import com.flowglass.engine.trigger.TriggerEnvironment
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerExecutor
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerPlan
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSchedule
import com.flowglass.engine.trigger.TriggerSkipRecorder
import com.flowglass.engine.trigger.TriggerSource
import com.flowglass.engine.trigger.TriggerSourceRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriggerEngineTest {
    private class FakeClock(var now: Long = 0) : Clock {
        override fun nowEpochMs(): Long = now
    }

    private class FakeEnvironment(
        var battery: Int? = null,
        var charging: Boolean = false,
        var screenOn: Boolean = true,
        var offsetMinutes: Int = 0,
    ) : TriggerEnvironment {
        override fun zoneOffsetMinutes(atEpochMs: Long): Int = offsetMinutes
        override fun batteryPercent(): Int? = battery
        override fun isCharging(): Boolean = charging
        override fun isScreenOn(): Boolean = screenOn
    }

    private class RecordingExecutor : TriggerExecutor {
        var running = false
        val calls = mutableListOf<Pair<TriggerRule, TriggerBinding>>()
        val reasons = mutableListOf<String>()
        val variables = mutableListOf<Map<String, Any?>>()

        override fun isRunning(rule: TriggerRule): Boolean = running

        override fun execute(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
        ): Boolean {
            if (running) return false
            calls += rule to binding
            reasons += reason
            this.variables += variables
            return true
        }
    }

    private class Skips {
        val records = mutableListOf<String>()
        val recorder = TriggerSkipRecorder { _, _, detail, _ -> records += detail }
    }

    private fun dispatcher(
        clock: FakeClock,
        env: FakeEnvironment,
        executor: RecordingExecutor,
        skips: Skips = Skips(),
    ) = TriggerDispatcher(clock, env, executor, skips.recorder)

    private fun trigger(typeId: String, vararg params: Pair<String, String>) = TriggerComponent(
        typeId = typeId,
        name = typeId.substringAfter('.'),
        params = params.associate { (key, value) -> key to JsonPrimitive(value) },
    )

    private fun rule(
        id: String,
        triggers: List<TriggerComponent>,
        conditions: List<TriggerComponent> = emptyList(),
        enabled: Boolean = true,
    ) = TriggerRule(id = id, cmdId = id.hashCode().toLong(), name = id, enabled = enabled,
        triggers = triggers, conditions = conditions)

    private fun screenEvent(kind: String = TriggerKinds.SCREEN_ON, at: Long = 0) =
        TriggerEvent(kind, TriggerSource.SCREEN, at)

    private fun batteryEvent(level: Int, at: Long = 0) =
        TriggerEvent(TriggerKinds.BATTERY, TriggerSource.BATTERY, at, mapOf("battery" to level.toString()))

    @Test
    fun triggerParametersMatchOnlyTheirOwnEvent() {
        val clock = FakeClock(0)
        val env = FakeEnvironment()
        val executor = RecordingExecutor()
        val d = dispatcher(clock, env, executor)
        val screenRule = rule("screen", listOf(trigger("trigger.screen_on")))

        val on = d.dispatch(screenEvent(TriggerKinds.SCREEN_ON), listOf(screenRule))
        assertEquals(1, on.executions.size)
        assertEquals("触发：screen_on", on.executions.single().reason)
        assertTrue(on.executions.single().variables["event.time"] is String)
        assertEquals(0, d.dispatch(screenEvent(TriggerKinds.SCREEN_OFF), listOf(screenRule)).executions.size)
        assertEquals(0, d.dispatch(screenEvent(TriggerKinds.USER_PRESENT), listOf(screenRule)).executions.size)
    }

    @Test
    fun timeAlarmMatchesOnlyItsTargetBinding() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val first = rule("first", listOf(trigger("trigger.time", "time" to "08:00", "days" to "1,2,3,4,5,6,7")))
        val second = rule("second", listOf(trigger("trigger.time", "time" to "08:00", "days" to "1,2,3,4,5,6,7")))
        val event = TriggerEvent(
            TriggerKinds.TIME, TriggerSource.TIME, 0,
            mapOf("time" to "08:00", "zone_offset_minutes" to "0"),
            targetRuleId = "second", targetTriggerIndex = 0,
        )

        val outcome = d.dispatch(event, listOf(first, second))

        assertEquals(listOf("second"), outcome.executions.map { it.rule.id })
    }

    @Test
    fun debounceCountsOneHitPerTriggerInsideTwoSeconds() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val r = rule("r", listOf(trigger("trigger.screen_on")))

        assertEquals(1, d.dispatch(screenEvent(at = 0), listOf(r)).executions.size)
        clock.now = 1_500
        val second = d.dispatch(screenEvent(at = 1_500), listOf(r))
        assertEquals(0, second.executions.size)
        assertEquals(1, second.debounced)
        clock.now = 2_000
        assertEquals(1, d.dispatch(screenEvent(at = 2_000), listOf(r)).executions.size)
        assertEquals(2, executor.calls.size)
    }

    @Test
    fun batteryTriggerFiresOnlyOnFalseToTrueCrossing() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val r = rule("battery", listOf(TriggerComponent(
            "trigger.battery_level", "battery_level",
            mapOf("direction" to JsonPrimitive("below"), "threshold" to JsonPrimitive(20)),
        )))
        val event = { level: Int -> batteryEvent(level, clock.now) }

        // First sample is the baseline, not a trigger.
        assertEquals(0, d.dispatch(event(25), listOf(r)).executions.size)
        clock.now = 3_000
        assertEquals(1, d.dispatch(event(15), listOf(r)).executions.size)
        clock.now = 6_000
        assertEquals(0, d.dispatch(event(10), listOf(r)).executions.size)
        clock.now = 9_000
        assertEquals(0, d.dispatch(event(25), listOf(r)).executions.size)
        clock.now = 12_000
        assertEquals(1, d.dispatch(event(15), listOf(r)).executions.size)
        assertEquals(2, executor.calls.size)
    }

    @Test
    fun allConditionsMustPassAndFailureRecordsWhy() {
        val base = 12 * 3_600_000L
        val clock = FakeClock(base)
        val env = FakeEnvironment(charging = false)
        val executor = RecordingExecutor()
        val skips = Skips()
        val d = dispatcher(clock, env, executor, skips)
        val r = rule(
            "guarded",
            listOf(trigger("trigger.screen_on")),
            conditions = listOf(
                TriggerComponent(
                    "condition.time_range", "time_range",
                    mapOf("range" to buildJsonObject {
                        put("from", JsonPrimitive("08:00")); put("to", JsonPrimitive("22:00"))
                    }),
                ),
                trigger("condition.charging", "state" to "charging"),
            ),
        )

        val first = d.dispatch(screenEvent(at = clock.now), listOf(r))
        assertEquals(0, first.executions.size)
        assertEquals(1, first.skips.size)
        assertEquals("条件不满足：charging", first.skips.single().detail)
        assertEquals(1, skips.records.size)

        // Same failure 30s later is debounced by the 2s window? No: it is a fresh hit but the
        // one-minute log window drops it. 90s later the log entry is allowed again.
        clock.now = base + 30_000
        assertEquals(0, d.dispatch(screenEvent(at = clock.now), listOf(r)).executions.size)
        assertEquals(1, skips.records.size)
        clock.now = base + 90_000
        assertEquals(0, d.dispatch(screenEvent(at = clock.now), listOf(r)).executions.size)
        assertEquals(2, skips.records.size)

        env.charging = true
        clock.now = base + 120_000
        assertEquals(1, d.dispatch(screenEvent(at = clock.now), listOf(r)).executions.size)
    }

    @Test
    fun skipRecordIsRateLimitedToOncePerMinutePerRule() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val skips = Skips()
        val d = dispatcher(clock, FakeEnvironment(charging = false), executor, skips)
        val r = rule("slow", listOf(trigger("trigger.screen_on")),
            conditions = listOf(trigger("condition.charging", "state" to "charging")))

        d.dispatch(screenEvent(at = 0), listOf(r))
        clock.now = 30_000
        d.dispatch(screenEvent(at = 30_000), listOf(r))
        assertEquals(1, skips.records.size)
        clock.now = 60_000
        d.dispatch(screenEvent(at = 60_000), listOf(r))
        assertEquals(2, skips.records.size)
    }

    @Test
    fun newTriggerIsDroppedWhileSameRuleIsRunning() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor().apply { running = true }
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val r = rule("busy", listOf(trigger("trigger.screen_on")))

        val outcome = d.dispatch(screenEvent(), listOf(r))

        assertEquals(0, outcome.executions.size)
        assertEquals(1, outcome.dropped)
        assertEquals(0, executor.calls.size)
    }

    @Test
    fun legacyBatteryLowAndChargingRulesStillMapToNewSources() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val low = rule("low", listOf(TriggerComponent(
            "trigger.battery_low", "battery_low", mapOf("threshold" to JsonPrimitive(20)),
        )))
        val charging = rule("charging", listOf(TriggerComponent(
            "trigger.charging", "charging", mapOf("state" to JsonPrimitive("charging")),
        )))
        val stopped = rule("stopped", listOf(TriggerComponent(
            "trigger.charging", "charging", mapOf("state" to JsonPrimitive("stopped")),
        )))

        assertEquals(0, d.dispatch(batteryEvent(25), listOf(low)).executions.size)
        clock.now = 3_000
        assertEquals(1, d.dispatch(batteryEvent(15), listOf(low)).executions.size)
        assertEquals(1, d.dispatch(
            TriggerEvent(TriggerKinds.POWER_CONNECTED, TriggerSource.POWER, 4_000),
            listOf(charging, stopped),
        ).executions.size)
        clock.now = 6_000
        assertEquals(
            listOf("stopped"),
            d.dispatch(TriggerEvent(TriggerKinds.POWER_DISCONNECTED, TriggerSource.POWER, 6_000),
                listOf(charging, stopped)).executions.map { it.rule.id },
        )
    }

    @Test
    fun sourceRegistryRegistersOneListenerAndKeepsItUntilLastRuleIsDisabled() {
        var installs = 0
        var uninstalls = 0
        val registry = TriggerSourceRegistry(
            install = { installs++ },
            uninstall = { uninstalls++ },
        )
        val screenA = rule("a", listOf(trigger("trigger.screen_on")))
        val screenB = rule("b", listOf(trigger("trigger.screen_off")))

        registry.update(TriggerPlan.requiredSources(listOf(screenA, screenB)))
        assertEquals(setOf(TriggerSource.SCREEN), registry.registered)
        assertEquals(1, installs)

        registry.update(TriggerPlan.requiredSources(listOf(screenA, screenB.copy(enabled = false))))
        assertEquals(setOf(TriggerSource.SCREEN), registry.registered)
        assertEquals(1, installs)
        assertEquals(0, uninstalls)

        registry.update(TriggerPlan.requiredSources(listOf(screenA.copy(enabled = false), screenB.copy(enabled = false))))
        assertTrue(registry.registered.isEmpty())
        assertEquals(1, uninstalls)
    }

    @Test
    fun unsupportedTriggersNeverRequestASource() {
        val unsupported = rule("wifi", listOf(trigger("trigger.wifi_changed", "ssid" to "home")))
        val supported = rule("screen", listOf(trigger("trigger.screen_on")))
        assertEquals(emptySet(), TriggerPlan.requiredSources(listOf(unsupported)))
        assertEquals(setOf(TriggerSource.SCREEN), TriggerPlan.requiredSources(listOf(supported, unsupported)))
    }

    @Test
    fun timeReminderChoosesTodayOrNextMatchingWeekday() {
        val noon = 12 * 3_600_000L // 1970-01-01 12:00 UTC, a Thursday (ISO 4)
        assertEquals(
            noon + 3_600_000L,
            TriggerSchedule.nextOccurrence(noon, 0, "13:00", "1,2,3,4,5,6,7"),
        )
        assertEquals(
            noon + 23 * 3_600_000L,
            TriggerSchedule.nextOccurrence(noon, 0, "11:00", "1,2,3,4,5,6,7"),
        )
        // Thursday noon + Friday 08:00.
        assertEquals(
            noon + 20 * 3_600_000L,
            TriggerSchedule.nextOccurrence(noon, 0, "08:00", "5"),
        )
        // Exactly at the target minute means the next occurrence, not an immediate loop.
        assertEquals(
            noon + 7 * 24 * 3_600_000L,
            TriggerSchedule.nextOccurrence(noon, 0, "12:00", "4"),
        )
        assertNull(TriggerSchedule.nextOccurrence(noon, 0, "25:00", "1,2,3,4,5,6,7"))
    }

    @Test
    fun weekdayAndMultiParameterCodecNormaliseStoredValues() {
        val meta = BuiltInComponents.registry().find("trigger.time")!!
        val text = ParameterCodec.formatComponent(meta, mapOf(
            "time" to JsonPrimitive("07:30"),
            "days" to ParameterCodec.parse(meta.params.single { it.key == "days" }, "周一、周三、周五"),
        ))
        assertEquals("07:30|周一、周三、周五", text)
        val parsed = ParameterCodec.parseComponent(meta, text)
        assertEquals("07:30", parsed.getValue("time").toString().trim('"'))
        assertEquals("1,3,5", parsed.getValue("days").toString().trim('"'))

        assertEquals("1,2,3,4,5", ParameterCodec.weekdays("工作日"))
        assertEquals("6,7", ParameterCodec.weekdays("周末"))
        assertEquals("1,2,3,4,5,6,7", ParameterCodec.weekdays("每天"))
        assertEquals("周一、周三", ParameterCodec.weekdayLabel("1,3"))
        assertFailsWith<IllegalArgumentException> { ParameterCodec.weekdays("周八") }
    }

    @Test
    fun wifiTriggerMatchesStateAndSsidOnlyAfterTheBaseline() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val r = rule("wifi", listOf(TriggerComponent(
            "trigger.wifi", "wifi",
            mapOf("state" to JsonPrimitive("connected"), "ssid" to JsonPrimitive("Home")),
        )))
        fun event(state: String, ssid: String) = TriggerEvent(
            TriggerKinds.WIFI, TriggerSource.WIFI, clock.now,
            mapOf("state" to state, "ssid" to ssid),
        )

        assertEquals(0, d.dispatch(event("connected", "Home"), listOf(r)).executions.size)
        clock.now = 3_000
        assertEquals(0, d.dispatch(event("disconnected", "Home"), listOf(r)).executions.size)
        clock.now = 6_000
        assertEquals(1, d.dispatch(event("connected", "Home"), listOf(r)).executions.size)
        clock.now = 9_000
        assertEquals(0, d.dispatch(event("disconnected", "Home"), listOf(r)).executions.size)
        clock.now = 12_000
        // A different SSID is not the configured network.
        assertEquals(0, d.dispatch(event("connected", "Cafe"), listOf(r)).executions.size)
        clock.now = 15_000
        assertEquals(0, d.dispatch(event("disconnected", "Cafe"), listOf(r)).executions.size)
        clock.now = 18_000
        assertEquals(1, d.dispatch(event("connected", "Home"), listOf(r)).executions.size)
    }

    @Test
    fun bluetoothNameRuleIgnoresOtherDevicesAndFiresOnMatch() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val r = rule("headphones", listOf(TriggerComponent(
            "trigger.bluetooth", "bluetooth",
            mapOf("state" to JsonPrimitive("connected"), "name" to JsonPrimitive("Sony")),
        )))
        fun event(state: String, name: String) = TriggerEvent(
            TriggerKinds.BLUETOOTH, TriggerSource.BLUETOOTH, clock.now,
            mapOf("state" to state, "name" to name),
        )

        // Baseline: the configured device is not connected.
        assertEquals(0, d.dispatch(event("disconnected", "Sony"), listOf(r)).executions.size)
        clock.now = 3_000
        assertEquals(0, d.dispatch(event("connected", "AirPods"), listOf(r)).executions.size)
        clock.now = 6_000
        assertEquals(1, d.dispatch(event("connected", "Sony WH-1000XM5"), listOf(r)).executions.size)
        clock.now = 9_000
        assertEquals(0, d.dispatch(event("connected", "Sony WH-1000XM5"), listOf(r)).executions.size)
    }

    @Test
    fun wifiDisconnectRuleMatchesTheSsidItWasConnectedTo() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val r = rule("leave-home", listOf(TriggerComponent(
            "trigger.wifi", "wifi",
            mapOf("state" to JsonPrimitive("disconnected"), "ssid" to JsonPrimitive("Home")),
        )))
        fun event(state: String, ssid: String) = TriggerEvent(
            TriggerKinds.WIFI, TriggerSource.WIFI, clock.now,
            mapOf("state" to state, "ssid" to ssid),
        )

        assertEquals(0, d.dispatch(event("connected", "Cafe"), listOf(r)).executions.size)
        clock.now = 3_000
        assertEquals(0, d.dispatch(event("disconnected", "Cafe"), listOf(r)).executions.size)
        clock.now = 6_000
        assertEquals(0, d.dispatch(event("connected", "Home"), listOf(r)).executions.size)
        clock.now = 9_000
        assertEquals(1, d.dispatch(event("disconnected", "Home"), listOf(r)).executions.size)
    }

    @Test
    fun notificationTriggerMatchesAppAndKeywordsAndPublishesVariables() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val r = rule("notification", listOf(TriggerComponent(
            "trigger.notification", "notification",
            mapOf(
                "app" to JsonPrimitive("com.example.chat"),
                "title" to JsonPrimitive("验证码"),
                "text" to JsonPrimitive("123456"),
            ),
        )))
        fun event(app: String, title: String, text: String) = TriggerEvent(
            TriggerKinds.NOTIFICATION, TriggerSource.NOTIFICATION, clock.now,
            mapOf("notification.app" to app, "notification.title" to title, "notification.text" to text),
        )

        assertEquals(0, d.dispatch(event("com.example.chat", "普通消息", "123456"), listOf(r)).executions.size)
        assertEquals(0, d.dispatch(event("com.other", "验证码", "123456"), listOf(r)).executions.size)
        val hit = d.dispatch(event("com.example.chat", "【验证码】", "您的验证码是 123456"), listOf(r))
        assertEquals(1, hit.executions.size)
        assertEquals("com.example.chat", hit.executions.single().variables["event.notification.app"])
        assertEquals("【验证码】", hit.executions.single().variables["event.notification.title"])
    }

    @Test
    fun appForegroundTriggerFiresOnEnterAndLeave() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        fun appRule(id: String, state: String) = rule(id, listOf(TriggerComponent(
            "trigger.app_foreground", "app_foreground",
            mapOf("state" to JsonPrimitive(state), "package" to JsonPrimitive("com.target")),
        )))
        val enter = appRule("enter", "foreground")
        val leave = appRule("leave", "background")
        fun event(app: String) = TriggerEvent(
            TriggerKinds.APP, TriggerSource.APP, clock.now, mapOf("app" to app),
        )

        // First observation is the baseline for each binding.
        assertEquals(0, d.dispatch(event("com.other"), listOf(enter, leave)).executions.size)
        clock.now = 3_000
        assertEquals(listOf("enter"), d.dispatch(event("com.target"), listOf(enter, leave)).executions.map { it.rule.id })
        clock.now = 6_000
        assertEquals(listOf("leave"), d.dispatch(event("com.other"), listOf(enter, leave)).executions.map { it.rule.id })
    }

    @Test
    fun bluetoothAndHeadsetTriggersUseBidirectionalTransitions() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val bt = rule("bt", listOf(TriggerComponent(
            "trigger.bluetooth", "bluetooth",
            mapOf("state" to JsonPrimitive("connected"), "name" to JsonPrimitive("Buds")),
        )))
        val headset = rule("headset", listOf(TriggerComponent(
            "trigger.headset", "headset",
            mapOf("state" to JsonPrimitive("plugged"), "kind" to JsonPrimitive("any")),
        )))
        fun btEvent(state: String, name: String) = TriggerEvent(
            TriggerKinds.BLUETOOTH, TriggerSource.BLUETOOTH, clock.now,
            mapOf("state" to state, "name" to name),
        )
        fun headsetEvent(state: String, kind: String) = TriggerEvent(
            TriggerKinds.HEADSET, TriggerSource.HEADSET, clock.now,
            mapOf("state" to state, "kind" to kind),
        )

        assertEquals(0, d.dispatch(btEvent("disconnected", "Buds"), listOf(bt)).executions.size)
        clock.now = 3_000
        assertEquals(1, d.dispatch(btEvent("connected", "Galaxy Buds"), listOf(bt)).executions.size)
        clock.now = 6_000
        assertEquals(0, d.dispatch(btEvent("disconnected", "Galaxy Buds"), listOf(bt)).executions.size)

        assertEquals(0, d.dispatch(headsetEvent("unplugged", "wired"), listOf(headset)).executions.size)
        clock.now = 9_000
        assertEquals(1, d.dispatch(headsetEvent("plugged", "bluetooth"), listOf(headset)).executions.size)
        clock.now = 12_000
        assertEquals(0, d.dispatch(headsetEvent("unplugged", "wired"), listOf(headset)).executions.size)
    }

    @Test
    fun newTriggerFamiliesRequestOneListenerEach() {
        val rules = listOf(
            rule("wifi", listOf(trigger("trigger.wifi", "state" to "connected"))),
            rule("bt", listOf(trigger("trigger.bluetooth", "state" to "connected"))),
            rule("notification", listOf(trigger("trigger.notification", "text" to "验证码"))),
            rule("app", listOf(trigger("trigger.app_foreground", "state" to "foreground", "package" to "com.x"))),
            rule("headset", listOf(trigger("trigger.headset", "state" to "plugged"))),
        )
        assertEquals(
            setOf(
                TriggerSource.WIFI, TriggerSource.BLUETOOTH, TriggerSource.NOTIFICATION,
                TriggerSource.APP, TriggerSource.HEADSET,
            ),
            TriggerPlan.requiredSources(rules),
        )
        // Disabling a rule drops its family; a shared family stays registered.
        assertEquals(
            setOf(TriggerSource.WIFI, TriggerSource.BLUETOOTH, TriggerSource.NOTIFICATION, TriggerSource.HEADSET),
            TriggerPlan.requiredSources(rules.map { if (it.id == "app") it.copy(enabled = false) else it }),
        )
    }
}
