package com.flowglass.engine

import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerDispatcher
import com.flowglass.engine.trigger.TriggerEnvironment
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerExecutor
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.NetworkType
import com.flowglass.engine.trigger.TriggerPlan
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSchedule
import com.flowglass.engine.trigger.TriggerSkipRecorder
import com.flowglass.engine.trigger.TriggerSource
import com.flowglass.engine.trigger.TriggerSourceRegistry
import com.flowglass.engine.trigger.TriggerTypes
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
        var dnd: Boolean? = false,
        var network: NetworkType = NetworkType.NONE,
        var processRunning: Boolean? = null,
        var ssid: String? = null,
    ) : TriggerEnvironment {
        override fun zoneOffsetMinutes(atEpochMs: Long): Int = offsetMinutes
        override fun batteryPercent(): Int? = battery
        override fun isCharging(): Boolean = charging
        override fun isScreenOn(): Boolean = screenOn
        override fun isDndOn(): Boolean? = dnd
        override fun networkType(): NetworkType = network
        override fun wifiSsid(): String? = ssid
        override fun appProcessRunning(packageName: String, userId: Int): Boolean? = processRunning
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
            readOnlyNames: Set<String>,
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
        mode: com.flowglass.engine.MatchMode = com.flowglass.engine.MatchMode.ANY,
    ) = TriggerRule(id = id, cmdId = id.hashCode().toLong(), name = id, enabled = enabled,
        triggers = triggers, conditions = conditions,
        triggerMatch = com.flowglass.engine.MatchSpec(mode = mode))

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
    fun appMethodCallMatchesTargetClassMethodAndTypes() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val r = rule(
            "hook",
            listOf(
                trigger(
                    TriggerTypes.APP_METHOD_CALL,
                    "package" to "com.example.target",
                    "className" to "com.example.Target",
                    "methodName" to "submit",
                    "paramTypes" to "java.lang.String",
                ),
            ),
        )

        // 方法钩子事件必须带上签发它的 hook id；这条规则的 cmdId 由 id 推导，组件 id 为 0。
        val hookId = com.flowglass.engine.methodhook.AppMethodCall.hookId(r.cmdId, 0)
        val miss = d.dispatch(
            TriggerEvent(TriggerKinds.METHOD_CALL, TriggerSource.METHOD_HOOK, 0,
                data = mapOf(
                    "app" to "com.example.target",
                    "method.class" to "com.example.Target",
                    "method.name" to "submit",
                    "method.parameterTypes" to "int",
                    "hook.id" to hookId,
                ), methodHookVariables = mapOf("订单号" to "B")),
            listOf(r),
        )
        assertEquals(0, miss.executions.size)

        clock.now = 3_000
        val hit = d.dispatch(
            TriggerEvent(TriggerKinds.METHOD_CALL, TriggerSource.METHOD_HOOK, 3_000,
                data = mapOf(
                    "app" to "com.example.target",
                    "method.class" to "com.example.Target",
                    "method.name" to "submit",
                    "method.parameterTypes" to "java.lang.String",
                    "hook.id" to hookId,
                ), methodHookVariables = mapOf("订单号" to "A-1")),
            listOf(r),
        )
        assertEquals(1, hit.executions.size)
        assertEquals("A-1", hit.executions.single().variables["订单号"])
        assertTrue(hit.executions.single().variables["event.time"] is String)
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
    fun notificationRegexModeIsOptInAndLiteralModeStaysLiteral() {
        val clock = FakeClock(0)
        val d = dispatcher(clock, FakeEnvironment(), RecordingExecutor())
        val regex = rule("regex", listOf(trigger(
            "trigger.notification", "title" to "验证码\\d+", "matchMode" to "regex",
        )))
        val literal = rule("literal", listOf(trigger(
            "trigger.notification", "title" to "验证码\\d+",
        )))
        fun event(title: String) = TriggerEvent(
            TriggerKinds.NOTIFICATION, TriggerSource.NOTIFICATION, clock.now,
            mapOf("notification.app" to "com.example", "notification.title" to title),
        )

        assertEquals(1, d.dispatch(event("您的验证码123"), listOf(regex)).executions.size)
        assertEquals(0, d.dispatch(event("您的验证码123"), listOf(literal)).executions.size)
        clock.now = 3_000
        assertEquals(0, d.dispatch(event("普通消息"), listOf(regex)).executions.size)
    }

    @Test
    fun notificationExactModeMatchesWholeFieldOnly() {
        val clock = FakeClock(0)
        val d = dispatcher(clock, FakeEnvironment(), RecordingExecutor())
        val exact = rule("exact", listOf(trigger(
            "trigger.notification", "title" to "验证码", "matchMode" to "exact",
        )))
        fun event(title: String) = TriggerEvent(
            TriggerKinds.NOTIFICATION, TriggerSource.NOTIFICATION, clock.now,
            mapOf("notification.app" to "com.example", "notification.title" to title),
        )

        assertEquals(0, d.dispatch(event("您的验证码"), listOf(exact)).executions.size)
        assertEquals(1, d.dispatch(event("验证码"), listOf(exact)).executions.size)
    }

    @Test
    fun invalidRegexFailsClosedInsteadOfCrashingTheDispatcher() {
        val clock = FakeClock(0)
        val d = dispatcher(clock, FakeEnvironment(), RecordingExecutor())
        val bad = rule("bad-regex", listOf(trigger(
            "trigger.notification", "title" to "(", "matchMode" to "regex",
        )))
        val event = TriggerEvent(
            TriggerKinds.NOTIFICATION, TriggerSource.NOTIFICATION, clock.now,
            mapOf("notification.app" to "com.example", "notification.title" to "anything"),
        )
        assertEquals(0, d.dispatch(event, listOf(bad)).executions.size)
    }

    @Test
    fun notificationTriggerKeepsCloneIdentitySeparateFromTheMainApp() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val r = rule("notification-clone", listOf(TriggerComponent(
            "trigger.notification", "notification",
            mapOf("app" to JsonPrimitive("com.example.chat@10")),
        )))
        fun event(app: String) = TriggerEvent(
            TriggerKinds.NOTIFICATION, TriggerSource.NOTIFICATION, clock.now,
            mapOf("notification.app" to app, "notification.title" to "", "notification.text" to ""),
        )

        assertEquals(0, d.dispatch(event("com.example.chat"), listOf(r)).executions.size)
        val hit = d.dispatch(event("com.example.chat@10"), listOf(r))
        assertEquals(1, hit.executions.size)
        assertEquals("com.example.chat@10", hit.executions.single().variables["event.notification.app"])
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
    fun keyguardTriggerMatchesOnlyTheConfiguredEdge() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val locked = rule("locked", listOf(TriggerComponent(
            "trigger.keyguard", "keyguard", mapOf("state" to JsonPrimitive("locked")),
        )))
        val unlocked = rule("unlocked", listOf(TriggerComponent(
            "trigger.keyguard", "keyguard", mapOf("state" to JsonPrimitive("unlocked")),
        )))
        val rules = listOf(locked, unlocked)
        fun event(state: String) = TriggerEvent(
            TriggerKinds.KEYGUARD, TriggerSource.SYSTEM, clock.now, mapOf("event" to state),
        )

        assertEquals(listOf("locked"), d.dispatch(event("locked"), rules).executions.map { it.rule.id })
        clock.now = 3_000
        assertEquals(listOf("unlocked"), d.dispatch(event("unlocked"), rules).executions.map { it.rule.id })
        assertEquals(0, d.dispatch(event("unknown"), rules).executions.size)
    }

    @Test
    fun mediaTriggerMatchesPackageAndPlaybackState() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val playing = rule("playing", listOf(TriggerComponent(
            "trigger.media", "media",
            mapOf("state" to JsonPrimitive("playing"), "package" to JsonPrimitive("com.music.app")),
        )))
        val rules = listOf(playing)
        fun event(kind: String, app: String) = TriggerEvent(
            kind, TriggerSource.SYSTEM, clock.now, mapOf("app" to app, "event" to "media"),
        )

        assertEquals(listOf("playing"), d.dispatch(event(TriggerKinds.MEDIA_PLAYING, "com.music.app@0"), rules)
            .executions.map { it.rule.id })
        assertEquals(0, d.dispatch(event(TriggerKinds.MEDIA_PAUSED, "com.music.app"), rules).executions.size)
        assertEquals(0, d.dispatch(event(TriggerKinds.MEDIA_PLAYING, "com.other.app"), rules).executions.size)
    }

    @Test
    fun foregroundServiceTriggerMatchesPackageAndEnterExit() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val started = rule("started", listOf(TriggerComponent(
            "trigger.foreground_service", "fgs",
            mapOf("state" to JsonPrimitive("started"), "package" to JsonPrimitive("com.example.app")),
        )))
        val rules = listOf(started)
        fun event(kind: String) = TriggerEvent(
            kind, TriggerSource.SYSTEM, clock.now, mapOf("app" to "com.example.app@0", "event" to "fgs"),
        )

        assertEquals(listOf("started"), d.dispatch(event(TriggerKinds.FOREGROUND_SERVICE_STARTED), rules)
            .executions.map { it.rule.id })
        assertEquals(0, d.dispatch(event(TriggerKinds.FOREGROUND_SERVICE_STOPPED), rules).executions.size)
    }

    @Test
    fun appProcessConditionUsesCachedStateAndFailsClosedWhenUnknown() {
        val env = FakeEnvironment(processRunning = null)
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, env, executor)
        val condition = TriggerComponent(
            "condition.app_process_state", "app_process_state",
            mapOf("package" to JsonPrimitive("com.target@10"), "state" to JsonPrimitive("running")),
        )
        val r = rule("condition", listOf(trigger("trigger.screen_on")), conditions = listOf(condition))
        val event = screenEvent(at = 1_000)

        assertEquals(0, d.dispatch(event, listOf(r)).executions.size)
        env.processRunning = true
        assertEquals(1, d.dispatch(event, listOf(r)).executions.size)
        env.processRunning = false
        assertEquals(0, d.dispatch(screenEvent(at = 2_000), listOf(r)).executions.size)
    }

    @Test
    fun processAndCrashTriggersMatchKindAndPackageWithoutBaseline() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val started = rule("started", listOf(TriggerComponent(
            "trigger.app_process", "app_process",
            mapOf("event" to JsonPrimitive("started"), "package" to JsonPrimitive("com.target@10")),
        )))
        val ended = rule("ended", listOf(TriggerComponent(
            "trigger.app_process", "app_process",
            mapOf("event" to JsonPrimitive("ended"), "package" to JsonPrimitive("com.target@10")),
        )))
        val crash = rule("crash", listOf(TriggerComponent(
            "trigger.app_crash", "app_crash",
            mapOf("event" to JsonPrimitive("crash"), "package" to JsonPrimitive("com.target@10")),
        )))
        val anr = rule("anr", listOf(TriggerComponent(
            "trigger.app_crash", "app_crash",
            mapOf("event" to JsonPrimitive("anr"), "package" to JsonPrimitive("com.target@10")),
        )))
        val rules = listOf(started, ended, crash, anr)
        fun event(kind: String, app: String) = TriggerEvent(
            kind, TriggerSource.SYSTEM, clock.now, mapOf("app" to app, "event" to kind),
        )

        assertEquals(listOf("started"), d.dispatch(event(TriggerKinds.APP_PROCESS_STARTED, "com.target@10"), rules).executions.map { it.rule.id })
        clock.now = 3_000
        assertEquals(listOf("ended"), d.dispatch(event(TriggerKinds.APP_PROCESS_ENDED, "com.target@10"), rules).executions.map { it.rule.id })
        clock.now = 6_000
        assertEquals(listOf("crash"), d.dispatch(event(TriggerKinds.APP_CRASH, "com.target@10"), rules).executions.map { it.rule.id })
        clock.now = 9_000
        assertEquals(listOf("anr"), d.dispatch(event(TriggerKinds.APP_ANR, "com.target@10"), rules).executions.map { it.rule.id })
        assertEquals(0, d.dispatch(event(TriggerKinds.APP_PROCESS_STARTED, "com.other"), rules).executions.size)
        assertEquals(0, d.dispatch(event(TriggerKinds.APP_PROCESS_STARTED, "com.target"), rules).executions.size)
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
    fun wifiAndBluetoothMatchModesAreAppliedToTheConfiguredPattern() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        val wifi = rule("wifi-contains", listOf(trigger(
            "trigger.wifi", "state" to "connected", "ssid" to "Home", "matchMode" to "contains",
        )))
        val bluetooth = rule("bt-regex", listOf(trigger(
            "trigger.bluetooth", "state" to "connected", "name" to "Buds|Headphones", "matchMode" to "regex",
        )))
        fun wifiEvent(state: String, ssid: String) = TriggerEvent(
            TriggerKinds.WIFI, TriggerSource.WIFI, clock.now,
            mapOf("state" to state, "ssid" to ssid),
        )
        fun btEvent(state: String, name: String) = TriggerEvent(
            TriggerKinds.BLUETOOTH, TriggerSource.BLUETOOTH, clock.now,
            mapOf("state" to state, "name" to name),
        )
        assertEquals(0, d.dispatch(wifiEvent("connected", "Office"), listOf(wifi)).executions.size)
        clock.now = 3_000
        assertEquals(1, d.dispatch(wifiEvent("connected", "Home 5G"), listOf(wifi)).executions.size)
        clock.now = 6_000
        assertEquals(0, d.dispatch(btEvent("disconnected", "Galaxy Buds"), listOf(bluetooth)).executions.size)
        clock.now = 9_000
        assertEquals(0, d.dispatch(btEvent("connected", "AirPods"), listOf(bluetooth)).executions.size)
        clock.now = 12_000
        assertEquals(1, d.dispatch(btEvent("connected", "Galaxy Buds"), listOf(bluetooth)).executions.size)
    }

    @Test
    fun newTriggerFamiliesRequestOneListenerEach() {
        val rules = listOf(
            rule("wifi", listOf(trigger("trigger.wifi", "state" to "connected"))),
            rule("bt", listOf(trigger("trigger.bluetooth", "state" to "connected"))),
            rule("notification", listOf(trigger("trigger.notification", "text" to "验证码"))),
            rule("app", listOf(trigger("trigger.app_foreground", "state" to "foreground", "package" to "com.x"))),
            rule("headset", listOf(trigger("trigger.headset", "state" to "plugged"))),
            rule("location", listOf(trigger("trigger.location_enter", "location" to "31.0,121.0", "radius" to "500"))),
        )
        assertEquals(
            setOf(
                TriggerSource.WIFI, TriggerSource.BLUETOOTH, TriggerSource.NOTIFICATION,
                TriggerSource.APP, TriggerSource.HEADSET, TriggerSource.LOCATION,
            ),
            TriggerPlan.requiredSources(rules),
        )
        // Disabling a rule drops its family; a shared family stays registered.
        assertEquals(
            setOf(TriggerSource.WIFI, TriggerSource.BLUETOOTH, TriggerSource.NOTIFICATION, TriggerSource.HEADSET, TriggerSource.LOCATION),
            TriggerPlan.requiredSources(rules.map { if (it.id == "app") it.copy(enabled = false) else it }),
        )
    }

    @Test
    fun locationEnterAndExitUseBaselineAndRadius() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, FakeEnvironment(), executor)
        fun locationRule(id: String, type: String) = rule(id, listOf(TriggerComponent(
            type, type.substringAfter('.'), mapOf(
                "location" to JsonPrimitive("31.0000,121.0000"),
                "radius" to JsonPrimitive(500),
            ),
        )))
        val enter = locationRule("enter", "trigger.location_enter")
        val exit = locationRule("exit", "trigger.location_exit")
        fun event(lat: Double, lng: Double) = TriggerEvent(
            TriggerKinds.LOCATION, TriggerSource.LOCATION, clock.now,
            mapOf("lat" to lat.toString(), "lng" to lng.toString()),
        )

        // First sample only establishes the baseline.
        assertEquals(0, d.dispatch(event(31.0200, 121.0000), listOf(enter, exit)).executions.size)
        clock.now = 3_000
        assertEquals(listOf("enter"), d.dispatch(event(31.0030, 121.0000), listOf(enter, exit)).executions.map { it.rule.id })
        clock.now = 6_000
        assertEquals(listOf("exit"), d.dispatch(event(31.0200, 121.0000), listOf(enter, exit)).executions.map { it.rule.id })
        clock.now = 9_000
        assertEquals(listOf("enter"), d.dispatch(event(31.0030, 121.0000), listOf(enter, exit)).executions.map { it.rule.id })
        // Outside the configured 500 m radius is a leave, even though it is still near the point.
        clock.now = 12_000
        assertEquals(listOf("exit"), d.dispatch(event(31.0200, 121.0000), listOf(enter, exit)).executions.map { it.rule.id })
    }

    @Test
    fun dndAndNetworkConditionsReadLiveEnvironment() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val env = FakeEnvironment(dnd = true, network = NetworkType.WIFI)
        val d = dispatcher(clock, env, executor)
        val r = rule(
            "guarded",
            listOf(trigger("trigger.screen_on")),
            conditions = listOf(
                TriggerComponent("condition.dnd_state", "勿扰状态", mapOf("state" to JsonPrimitive("on"))),
                TriggerComponent("condition.network_type", "网络类型", mapOf("network" to JsonPrimitive("wifi"))),
            ),
        )
        assertEquals(1, d.dispatch(screenEvent(), listOf(r)).executions.size)

        env.dnd = false
        env.network = NetworkType.MOBILE
        clock.now = 3_000
        val skipped = d.dispatch(screenEvent(at = 3_000), listOf(r))
        assertEquals(0, skipped.executions.size)
        assertEquals("条件不满足：勿扰状态、网络类型", skipped.skips.single().detail)
    }

    @Test
    fun allModeWifiUsesTheConfiguredSsid() {
        val clock = FakeClock(0)
        val env = FakeEnvironment(network = NetworkType.WIFI, ssid = "Cafe")
        val executor = RecordingExecutor()
        val d = dispatcher(clock, env, executor)
        val home = rule(
            "home",
            listOf(
                trigger(TriggerTypes.WIFI, "state" to "connected", "ssid" to "Home"),
                trigger(TriggerTypes.SCREEN_ON),
            ),
            mode = com.flowglass.engine.MatchMode.ALL,
        )

        assertEquals(0, d.dispatch(screenEvent(), listOf(home)).executions.size)
        env.ssid = "Home"
        assertEquals(1, d.dispatch(screenEvent(at = 3_000), listOf(home)).executions.size)
    }

    @Test
    fun allModeWifiDisconnectedMeansThatSsidIsNotTheCurrentOne() {
        val clock = FakeClock(0)
        val env = FakeEnvironment(network = NetworkType.WIFI, ssid = "Cafe")
        val d = dispatcher(clock, env, RecordingExecutor())
        val leftHome = rule(
            "left-home",
            listOf(
                trigger(TriggerTypes.WIFI, "state" to "disconnected", "ssid" to "Home"),
                trigger(TriggerTypes.SCREEN_ON),
            ),
            mode = com.flowglass.engine.MatchMode.ALL,
        )

        assertEquals(1, d.dispatch(screenEvent(), listOf(leftHome)).executions.size)
        env.ssid = "Home"
        assertEquals(0, d.dispatch(screenEvent(at = 3_000), listOf(leftHome)).executions.size)
    }

    @Test
    fun allModeWifiWithoutAReadableSsidMatchesNeitherDirection() {
        val clock = FakeClock(0)
        val env = FakeEnvironment(network = NetworkType.WIFI, ssid = null)
        val d = dispatcher(clock, env, RecordingExecutor())
        val connected = rule(
            "connected",
            listOf(
                trigger(TriggerTypes.WIFI, "state" to "connected", "ssid" to "Home"),
                trigger(TriggerTypes.SCREEN_ON),
            ),
            mode = com.flowglass.engine.MatchMode.ALL,
        )
        val disconnected = rule(
            "disconnected",
            listOf(
                trigger(TriggerTypes.WIFI, "state" to "disconnected", "ssid" to "Home"),
                trigger(TriggerTypes.SCREEN_ON),
            ),
            mode = com.flowglass.engine.MatchMode.ALL,
        )

        assertEquals(0, d.dispatch(screenEvent(), listOf(connected, disconnected)).executions.size)
    }

    @Test
    fun allModeWifiDisconnectedWithoutSsidFallsBackToNetworkType() {
        val clock = FakeClock(0)
        val env = FakeEnvironment(network = NetworkType.NONE)
        val d = dispatcher(clock, env, RecordingExecutor())
        val offWifi = rule(
            "off-wifi",
            listOf(
                trigger(TriggerTypes.WIFI, "state" to "disconnected", "ssid" to ""),
                trigger(TriggerTypes.SCREEN_ON),
            ),
            mode = com.flowglass.engine.MatchMode.ALL,
        )

        assertEquals(1, d.dispatch(screenEvent(), listOf(offWifi)).executions.size)
        env.network = NetworkType.WIFI
        env.ssid = "Cafe"
        assertEquals(0, d.dispatch(screenEvent(at = 3_000), listOf(offWifi)).executions.size)
    }
}
