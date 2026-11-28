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
import kotlin.test.assertNotNull

/** Clipboard trigger metadata, matching and variable delivery. */
class ClipboardTriggerTest {
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
        val variables = mutableListOf<Map<String, Any?>>()

        override fun isRunning(rule: TriggerRule): Boolean = false

        override fun execute(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
            readOnlyNames: Set<String>,
        ): Boolean {
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

    private fun trigger() = TriggerComponent(
        typeId = TriggerTypes.CLIPBOARD_CHANGED,
        name = "剪贴板发生变化",
        params = emptyMap(),
    )

    private fun rule(id: String = "clipboard") = TriggerRule(
        id = id,
        cmdId = id.hashCode().toLong(),
        name = id,
        enabled = true,
        triggers = listOf(trigger()),
    )

    private fun event(at: Long = 0, text: String = "hello") = TriggerEvent(
        kind = TriggerKinds.CLIPBOARD_CHANGED,
        source = TriggerSource.CLIPBOARD,
        atEpochMs = at,
        data = mapOf(
            "clipboard.text" to text,
            "clipboard.package" to "com.android.chrome",
            "clipboard.time" to "2026-11-27 20:14:00",
            "clipboard.mime" to "text/plain",
        ),
    )

    @Test
    fun metadataDeclaresClipboardOutputsAndLayers() {
        val meta = BuiltInComponents.registry().find(TriggerTypes.CLIPBOARD_CHANGED)
        assertNotNull(meta)
        assertEquals("剪贴板发生变化", meta.name)
        assertEquals(TriggerSource.CLIPBOARD, TriggerTypes.sourceOf(TriggerTypes.CLIPBOARD_CHANGED))
        assertEquals(
            listOf("剪贴板内容", "来源应用", "变化时间", "内容类型"),
            meta.outputs.map { it.label },
        )
        assertEquals(
            listOf(
                com.flowglass.engine.ExecutionLayer.XPOSED,
                com.flowglass.engine.ExecutionLayer.NORMAL,
            ),
            meta.supports.map { it.layer },
        )
    }

    @Test
    fun dispatchInjectsClipboardVariables() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val result = dispatcher(clock, executor).dispatch(event(), listOf(rule()))
        assertEquals(1, result.executions.size)
        val values = executor.variables.single()
        assertEquals("hello", values["剪贴板内容"])
        assertEquals("com.android.chrome", values["剪贴板来源应用"])
        assertEquals("2026-11-27 20:14:00", values["剪贴板时间"])
        assertEquals("text/plain", values["剪贴板类型"])
    }

    @Test
    fun emptyBodyDoesNotMatchClipboardTrigger() {
        val executor = RecordingExecutor()
        val result = dispatcher(FakeClock(0), executor).dispatch(
            event(text = "   "),
            listOf(rule()),
        )
        assertEquals(0, result.executions.size)
        assertEquals(0, executor.variables.size)
    }

    @Test
    fun debounceStillAppliesToRepeatedClipboardEvents() {
        val clock = FakeClock(0)
        val executor = RecordingExecutor()
        val d = dispatcher(clock, executor)
        assertEquals(1, d.dispatch(event(0), listOf(rule())).executions.size)
        clock.now = 1_000
        assertEquals(0, d.dispatch(event(1_000, "next"), listOf(rule())).executions.size)
        clock.now = 2_000
        assertEquals(1, d.dispatch(event(2_000, "later"), listOf(rule())).executions.size)
    }
}
