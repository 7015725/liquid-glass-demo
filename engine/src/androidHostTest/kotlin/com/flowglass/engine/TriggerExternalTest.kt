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

/** Behaviour of the explicit-entry triggers added in M2. */
class TriggerExternalTest {
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
    fun intentNeedsMatchingTokenAndOptionalAction() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("intent", trigger(TriggerTypes.INTENT_RECEIVED,
            "token" to "s3cret", "action" to "com.example.RUN"))
        assertEquals(0, d.dispatch(event(TriggerKinds.INTENT_RECEIVED, 0, TriggerSource.INTENT,
            "intent.token" to "wrong", "intent.action" to "com.example.RUN"), listOf(r)).executions.size)
        assertEquals(0, d.dispatch(event(TriggerKinds.INTENT_RECEIVED, 3_000, TriggerSource.INTENT,
            "intent.token" to "s3cret", "intent.action" to "com.example.OTHER"), listOf(r)).executions.size)
        assertEquals(1, d.dispatch(event(TriggerKinds.INTENT_RECEIVED, 6_000, TriggerSource.INTENT,
            "intent.token" to "s3cret", "intent.action" to "com.example.RUN",
            "intent.data" to "https://example.com"), listOf(r)).executions.size)
        assertEquals("https://example.com", executor.variables.single()["Intent数据"])
    }

    @Test
    fun httpNeedsTokenAndAppliesMethodAndPathFilters() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val r = rule("http", trigger(TriggerTypes.HTTP_REQUEST,
            "token" to "hook-token", "method" to "POST", "path" to "/hook"))
        assertEquals(0, d.dispatch(event(TriggerKinds.HTTP_REQUEST, 0, TriggerSource.HTTP,
            "http.token" to "wrong", "http.method" to "POST", "http.path" to "/hook"), listOf(r)).executions.size)
        assertEquals(0, d.dispatch(event(TriggerKinds.HTTP_REQUEST, 3_000, TriggerSource.HTTP,
            "http.token" to "hook-token", "http.method" to "GET", "http.path" to "/hook"), listOf(r)).executions.size)
        assertEquals(1, d.dispatch(event(TriggerKinds.HTTP_REQUEST, 6_000, TriggerSource.HTTP,
            "http.token" to "hook-token", "http.method" to "POST", "http.path" to "/hook",
            "http.body" to "hello"), listOf(r)).executions.size)
        assertEquals("hello", executor.variables.single()["正文"])
    }

    @Test
    fun intentAndHttpWithoutTokenAreOpenEntries() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val intent = rule("open-intent", trigger(TriggerTypes.INTENT_RECEIVED, "token" to ""))
        val http = rule("open-http", trigger(TriggerTypes.HTTP_REQUEST, "token" to "",
            "method" to "POST", "path" to "/hook"))

        assertEquals(1, d.dispatch(event(TriggerKinds.INTENT_RECEIVED, 0, TriggerSource.INTENT,
            "intent.token" to "", "intent.action" to "com.example.RUN"), listOf(intent)).executions.size)
        clock.now = 3_000
        assertEquals(1, d.dispatch(event(TriggerKinds.HTTP_REQUEST, 3_000, TriggerSource.HTTP,
            "http.token" to "", "http.method" to "POST", "http.path" to "/hook"), listOf(http)).executions.size)
    }

    @Test
    fun shareKindAndTileOnlyFireTheirOwnBinding() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        val text = rule("text", trigger(TriggerTypes.SHARE_RECEIVED, "kind" to "text"))
        val image = rule("image", trigger(TriggerTypes.SHARE_RECEIVED, "kind" to "image"))
        assertEquals(1, d.dispatch(event(TriggerKinds.SHARE_RECEIVED, 0, TriggerSource.SHARE,
            "share.mime" to "text/plain", "share.text" to "hi"), listOf(text, image)).executions.size)
        assertEquals("text", executor.calls.single().first.id)

        clock.now = 3_000
        assertEquals(1, d.dispatch(event(TriggerKinds.SHARE_RECEIVED, 3_000, TriggerSource.SHARE,
            "share.mime" to "image/png", "share.uri" to "content://x/1"), listOf(text, image)).executions.size)
        assertEquals("image", executor.calls.last().first.id)

        clock.now = 6_000
        val tile = rule("tile", trigger(TriggerTypes.QUICK_SETTING))
        assertEquals(1, d.dispatch(event(TriggerKinds.QUICK_SETTING, 6_000, TriggerSource.TILE),
            listOf(tile)).executions.size)
    }
}
