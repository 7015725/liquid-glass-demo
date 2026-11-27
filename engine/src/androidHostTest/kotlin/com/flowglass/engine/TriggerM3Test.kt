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

/** Behaviour of the phone/SMS/calendar/media and notification-removal triggers added in M3. */
class TriggerM3Test {
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
        params = params.associate { (key, value) -> key to JsonPrimitive(value.toString()) },
    )

    private fun rule(id: String, trigger: TriggerComponent) = TriggerRule(
        id = id,
        cmdId = id.hashCode().toLong(),
        name = id,
        enabled = true,
        triggers = listOf(trigger),
    )

    private fun event(
        kind: String,
        at: Long,
        source: TriggerSource,
        vararg data: Pair<String, String>,
    ) = TriggerEvent(kind, source, at, data.toMap())

    @Test
    fun callStateAndMissedCallAreSeparateEvents() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val ringing = rule("ringing", trigger(TriggerTypes.CALL_STATE, "event" to "ringing"))
        val missed = rule("missed", trigger(TriggerTypes.MISSED_CALL))
        assertEquals(1, d.dispatch(event(TriggerKinds.CALL_STATE, 0, TriggerSource.PHONE,
            "call.state" to "ringing"), listOf(ringing, missed)).executions.size)
        assertEquals("ringing", executor.calls.single().first.id)
        clock.now = 3_000
        assertEquals(0, d.dispatch(event(TriggerKinds.CALL_STATE, 3_000, TriggerSource.PHONE,
            "call.state" to "idle"), listOf(ringing, missed)).executions.size)
        clock.now = 6_000
        assertEquals(1, d.dispatch(event(TriggerKinds.MISSED_CALL, 6_000, TriggerSource.PHONE),
            listOf(ringing, missed)).executions.size)
        assertEquals("missed", executor.calls.last().first.id)
    }

    @Test
    fun smsFiltersSenderAndKeyword() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("sms", trigger(TriggerTypes.SMS_RECEIVED,
            "sender" to "10086", "keyword" to "账单"))
        assertEquals(0, d.dispatch(event(TriggerKinds.SMS_RECEIVED, 0, TriggerSource.SMS,
            "sms.sender" to "10010", "sms.body" to "账单到了"), listOf(r)).executions.size)
        assertEquals(0, d.dispatch(event(TriggerKinds.SMS_RECEIVED, 3_000, TriggerSource.SMS,
            "sms.sender" to "10086", "sms.body" to "欢迎"), listOf(r)).executions.size)
        assertEquals(1, d.dispatch(event(TriggerKinds.SMS_RECEIVED, 6_000, TriggerSource.SMS,
            "sms.sender" to "10086", "sms.body" to "本月账单到了"), listOf(r)).executions.size)
        assertEquals("本月账单到了", executor.variables.single()["短信内容"])
    }

    @Test
    fun notificationExtensionAndRemovalAreDifferentKinds() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val posted = rule("posted", trigger(TriggerTypes.NOTIFICATION,
            "category" to "msg", "ongoing" to "yes"))
        val removed = rule("removed", trigger(TriggerTypes.NOTIFICATION_REMOVED,
            "app" to "com.example.chat", "text" to "hello"))
        assertEquals(0, d.dispatch(event(TriggerKinds.NOTIFICATION, 0, TriggerSource.NOTIFICATION,
            "notification.app" to "com.example.chat", "notification.text" to "hello",
            "notification.category" to "msg", "notification.ongoing" to "false"),
            listOf(posted, removed)).executions.size)
        clock.now = 3_000
        assertEquals(1, d.dispatch(event(TriggerKinds.NOTIFICATION, 3_000, TriggerSource.NOTIFICATION,
            "notification.app" to "com.example.chat", "notification.text" to "hello",
            "notification.category" to "msg", "notification.ongoing" to "true"),
            listOf(posted, removed)).executions.size)
        assertEquals("posted", executor.calls.single().first.id)
        clock.now = 6_000
        assertEquals(1, d.dispatch(event(TriggerKinds.NOTIFICATION_REMOVED, 6_000, TriggerSource.NOTIFICATION,
            "notification.app" to "com.example.chat", "notification.text" to "hello"),
            listOf(posted, removed)).executions.size)
        assertEquals("removed", executor.calls.last().first.id)
    }

    @Test
    fun mediaTrackFiltersPackageAndCalendarOnlyFiresItsType() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val media = rule("media", trigger(TriggerTypes.MEDIA_TRACK, "app" to "com.example.music"))
        val calendar = rule("calendar", trigger(TriggerTypes.CALENDAR_CHANGED))
        assertEquals(0, d.dispatch(event(TriggerKinds.MEDIA_TRACK, 0, TriggerSource.MEDIA,
            "media.package" to "com.example.video", "media.title" to "A"), listOf(media, calendar)).executions.size)
        assertEquals(1, d.dispatch(event(TriggerKinds.MEDIA_TRACK, 3_000, TriggerSource.MEDIA,
            "media.package" to "com.example.music", "media.title" to "B"), listOf(media, calendar)).executions.size)
        assertEquals("media", executor.calls.single().first.id)
        clock.now = 6_000
        assertEquals(1, d.dispatch(event(TriggerKinds.CALENDAR_CHANGED, 6_000, TriggerSource.CALENDAR),
            listOf(media, calendar)).executions.size)
        assertEquals("calendar", executor.calls.last().first.id)
    }
}
