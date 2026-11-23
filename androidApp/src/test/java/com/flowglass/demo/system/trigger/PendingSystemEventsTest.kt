package com.flowglass.demo.system.trigger

import android.content.Context
import android.content.Intent
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TRG-003：后台 FGS 启动被拒时事件必须落盘，而不是静默丢弃。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingSystemEventsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun aRejectedEventSurvivesUntilTheServiceCanReplayIt() {
        val intent = Intent().setAction("ev").putExtra("event", "app_started")
            .putExtra("package", "com.example").putExtra("user", 10).putExtra("time", 42L)

        PendingSystemEvents.enqueue(context, intent)
        assertTrue(PendingSystemEvents.isNotEmpty(context))

        val drained = PendingSystemEvents.drain(context)
        assertEquals(1, drained.size)
        assertEquals("ev", drained.single().action)
        assertEquals("app_started", drained.single().getStringExtra("event"))
        assertEquals(10, drained.single().getIntExtra("user", -1))
        assertEquals(42L, drained.single().getLongExtra("time", 0L))
        assertFalse(PendingSystemEvents.isNotEmpty(context))
    }

    @Test
    fun aRejectedServiceStartQueuesTheEvent() {
        SystemHookReceiver().startOrQueue(
            context,
            TriggerService.ACTION_SYSTEM_EVENT,
            start = { false },
        ) {
            putExtra("event", "app_started")
            putExtra("package", "com.example")
            putExtra("user", 3)
            putExtra("time", 7L)
        }
        val drained = PendingSystemEvents.drain(context)
        assertEquals(1, drained.size)
        assertEquals("app_started", drained.single().getStringExtra("event"))
        assertEquals(3, drained.single().getIntExtra("user", -1))
    }

    @Test
    fun theQueueKeepsOnlyTheNewestEvents() {
        repeat(PendingSystemEvents.MAX + 8) { index ->
            PendingSystemEvents.enqueue(context, Intent().putExtra("index", index))
        }
        val drained = PendingSystemEvents.drain(context)
        assertEquals(PendingSystemEvents.MAX, drained.size)
        assertEquals(8, drained.first().getIntExtra("index", -1))
        assertEquals(PendingSystemEvents.MAX + 7, drained.last().getIntExtra("index", -1))
    }
}
