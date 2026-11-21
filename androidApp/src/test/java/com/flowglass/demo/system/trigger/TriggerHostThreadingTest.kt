package com.flowglass.demo.system.trigger

import android.os.Looper
import com.flowglass.demo.ui.AppState
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Trigger evaluation reads file conditions through a blocking Root probe. It must never run on the
 * thread that draws the UI, and the completion callback must not touch the dispatcher from another
 * thread, so all host work belongs to one serial worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TriggerHostThreadingTest {
    private val mainThreadId: Long = Thread.currentThread().id

    private fun rule(): Cmd = Cmd(
        id = 1L, auto = true, ic = Ic.Timer, title = "早上提醒", desc = "",
        triggers = listOf(CommandItem(11L, "定时", "08:00|1,2,3,4,5,6,7", Ic.Timer, "trigger.time")),
        actions = listOf(CommandItem(12L, "提示", "ok", Ic.Message, "action.toast")),
        enabled = true,
    )

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun aSecondStopDoesNotCancelTheQueuedTeardown() {
        // ACTION_STOP calls stop(true) and the service's onDestroy then calls stop(false). The
        // second call must not invalidate the teardown that still has to release receivers,
        // alarms and the dispatcher.
        val state = AppState().apply { setEngineRunning(true); autoCmds.add(rule()) }
        val host = TriggerHost(
            context = RuntimeEnvironment.getApplication(),
            state = state,
            scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
            onStatus = { _, _ -> },
            onShouldStop = {},
        )
        host.start()
        await { host.isRunning }
        TriggerStatus.serviceActive = true

        host.stop(cancelAlarms = true)
        host.stop(cancelAlarms = false)
        await { !TriggerStatus.serviceActive }

        assertFalse("重复 stop 不得让排队的清理失效", TriggerStatus.serviceActive)
        assertEquals("清理后不应残留已注册的触发源", 0, TriggerStatus.registeredSources)
    }

    @Test
    fun restartAfterStopKeepsTheNewSessionAlive() {
        val state = AppState().apply { setEngineRunning(true); autoCmds.add(rule()) }
        val threads = CopyOnWriteArrayList<Long>()
        val host = TriggerHost(
            context = RuntimeEnvironment.getApplication(),
            state = state,
            scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
            onStatus = { _, _ -> threads += Thread.currentThread().id },
            onShouldStop = {},
        )
        try {
            host.start()
            await { threads.isNotEmpty() }
            assertTrue("首次启动应完成状态同步", threads.isNotEmpty())

            host.stop(cancelAlarms = false)
            host.start()
            await { threads.size >= 2 }

            assertTrue("停止后重新启动必须重新同步状态（旧的清理不得杀掉新会话）", threads.size >= 2)
            assertEquals("新会话也必须落在同一个 worker 上", threads.first(), threads.last())
        } finally {
            host.stop(cancelAlarms = false)
        }
    }

    @Test
    fun triggerWorkRunsOffTheMainThreadOnOneWorker() {
        val state = AppState().apply { setEngineRunning(true); autoCmds.add(rule()) }
        val threads = CopyOnWriteArrayList<Long>()
        val names = CopyOnWriteArrayList<String>()
        val host = TriggerHost(
            context = RuntimeEnvironment.getApplication(),
            state = state,
            scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
            onStatus = { _, _ ->
                threads += Thread.currentThread().id
                names += Thread.currentThread().name
            },
            onShouldStop = {},
        )
        try {
            host.start()
            await { threads.isNotEmpty() }

            assertTrue("启动后应完成一次状态同步", threads.isNotEmpty())
            assertFalse(
                "触发器求值不得在 UI 线程执行，实际线程=${names.first()}",
                threads.first() == mainThreadId,
            )

            state.setEnabled(1L, false)
            state.setEnabled(1L, true)
            await { threads.size >= 2 }

            assertTrue("规则变化后应再次同步状态", threads.size >= 2)
            assertTrue(
                "触发器工作必须在专用线程上，实际=${names.distinct()}",
                names.all { it.startsWith(TriggerWorker.THREAD_NAME) },
            )
            assertEquals(
                "所有触发器状态同步应落在同一个串行 worker 上，实际=${names.distinct()}",
                threads.first(),
                threads.last(),
            )
        } finally {
            host.stop(cancelAlarms = false)
        }
    }
}
