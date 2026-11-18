package com.flowglass.demo.system.trigger

import android.os.Handler
import android.os.Looper
import com.flowglass.engine.Clock
import com.flowglass.engine.trigger.NetworkType
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerDispatcher
import com.flowglass.engine.trigger.TriggerEnvironment
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerExecutor
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSkipRecorder
import com.flowglass.engine.trigger.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The trigger lane must be serial, off the main thread, and remain ordered even when the first
 * condition is slow. The fake environment sleeps in fileExists, matching a Root probe that waits
 * for a root shell.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TriggerEventWorkerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun trigger(typeId: String, vararg params: Pair<String, String>) = TriggerComponent(
        typeId = typeId,
        name = typeId.substringAfter('.'),
        params = params.associate { (key, value) -> key to JsonPrimitive(value) },
    )

    private fun rule(conditions: List<TriggerComponent> = emptyList()) = TriggerRule(
        id = "thread-test",
        cmdId = 42L,
        name = "thread-test",
        enabled = true,
        triggers = listOf(trigger("trigger.screen_on")),
        conditions = conditions,
    )

    private fun screenEvent() = TriggerEvent(TriggerKinds.SCREEN_ON, TriggerSource.SCREEN, 0)

    @Test
    fun slowRootFileConditionDoesNotBlockMainThread() {
        val checked = CountDownLatch(1)
        val executed = CountDownLatch(1)
        val environment = object : TriggerEnvironment {
            override fun zoneOffsetMinutes(atEpochMs: Long): Int = 0
            override fun batteryPercent(): Int? = 80
            override fun isCharging(): Boolean = false
            override fun isScreenOn(): Boolean = true
            override fun isDndOn(): Boolean? = false
            override fun networkType(): NetworkType = NetworkType.NONE
            override fun fileExists(path: String): Boolean? {
                checked.countDown()
                // Simulates Root.check + RootSessions.run taking three seconds.
                Thread.sleep(3_000)
                return true
            }
        }
        val executor = object : TriggerExecutor {
            override suspend fun isRunning(rule: TriggerRule): Boolean = false
            override suspend fun execute(
                rule: TriggerRule,
                binding: com.flowglass.engine.trigger.TriggerBinding,
                reason: String,
                variables: Map<String, Any?>,
            ): Boolean {
                executed.countDown()
                return true
            }
        }
        val dispatcher = TriggerDispatcher(
            clock = Clock { 0 },
            environment = environment,
            executor = executor,
            skipRecorder = TriggerSkipRecorder { _, _, _, _ -> },
        )
        val fileRule = rule(
            listOf(trigger("condition.variable", "left" to "/data/adb", "op" to "file_exists")),
        )
        val worker = TriggerEventWorker(scope, handler = { event ->
            dispatcher.dispatch(event, listOf(fileRule))
        })
        worker.start()

        val mainProbe = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { mainProbe.countDown() }
        worker.emit(screenEvent())

        assertTrue("slow root check did not start", checked.await(2, TimeUnit.SECONDS))
        // The worker is sleeping in fileExists here. Main must still be able to run tasks.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("main thread was blocked by the slow root check", mainProbe.await(1, TimeUnit.SECONDS))
        assertTrue("trigger never reached execution", executed.await(5, TimeUnit.SECONDS))
        worker.stop()
    }

    @Test
    fun queuedEventsKeepTheirArrivalOrder() {
        val firstStarted = CountDownLatch(1)
        val received = mutableListOf<String>()
        val bothDone = CountDownLatch(2)
        val worker = TriggerEventWorker(scope, handler = { event ->
            val n = event.data["n"].orEmpty()
            received += n
            if (n == "1") {
                firstStarted.countDown()
                Thread.sleep(200)
            }
            bothDone.countDown()
        })
        worker.start()
        worker.emit(TriggerEvent(TriggerKinds.SCREEN_ON, TriggerSource.SCREEN, 0, mapOf("n" to "1")))
        worker.emit(TriggerEvent(TriggerKinds.SCREEN_ON, TriggerSource.SCREEN, 0, mapOf("n" to "2")))

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        assertTrue(bothDone.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("1", "2"), received)
        worker.stop()
    }

    @Test
    fun eventFloodDoesNotDropRuleSyncControl() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val controlRan = CountDownLatch(1)
        val events = java.util.concurrent.atomic.AtomicInteger()
        val worker = TriggerEventWorker(scope, handler = {
            if (events.incrementAndGet() == 1) {
                firstStarted.countDown()
                releaseFirst.await(3, TimeUnit.SECONDS)
            }
        })
        worker.start()
        repeat(200) {
            worker.emit(TriggerEvent(TriggerKinds.SCREEN_ON, TriggerSource.SCREEN, 0, mapOf("n" to "$it")))
        }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        // The event queue is full at this point; rule sync must still be accepted and run.
        worker.submit {
            controlRan.countDown()
        }
        releaseFirst.countDown()
        assertTrue("control command was dropped during an event flood", controlRan.await(5, TimeUnit.SECONDS))
        worker.stop()
    }

    @Test
    fun stopThenStartDoesNotRunCommandsQueuedBeforeStop() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldCommand = java.util.concurrent.atomic.AtomicInteger()
        val newCommand = CountDownLatch(1)
        val worker = TriggerEventWorker(scope, handler = {
            blocked.countDown()
            release.await(3, TimeUnit.SECONDS)
        })
        worker.start()
        worker.emit(TriggerEvent(TriggerKinds.SCREEN_ON, TriggerSource.SCREEN, 0))
        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        worker.submit { oldCommand.incrementAndGet() }
        worker.stop()
        release.countDown()
        worker.start()
        worker.submit { newCommand.countDown() }
        assertTrue(newCommand.await(5, TimeUnit.SECONDS))
        // Give a leaked old command a chance to run before asserting none did.
        Thread.sleep(100)
        assertEquals(0, oldCommand.get())
        worker.stop()
    }

    @Test
    fun stopAfterFinalizerRunsOffMainThread() {
        val finalized = CountDownLatch(1)
        val onMain = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = TriggerEventWorker(scope, handler = {})
        worker.start()
        worker.stopAfter {
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            finalized.countDown()
        }
        assertTrue(finalized.await(3, TimeUnit.SECONDS))
        assertEquals(false, onMain.get())
        worker.stop()
    }
}
