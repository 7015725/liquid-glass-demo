package com.flowglass.demo.system.trigger

import android.os.Looper
import com.flowglass.engine.Clock
import com.flowglass.engine.trigger.NetworkType
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TriggerDispatcherLaneTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainLooper = Looper.getMainLooper()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun dispatcherAccessesAllRunOnTheSerialWorker() {
        val seen = ConcurrentHashMap<String, Boolean>()
        val done = CountDownLatch(4)
        val executor = object : TriggerExecutor {
            override suspend fun isRunning(rule: TriggerRule): Boolean = false
            override suspend fun execute(
                rule: TriggerRule,
                binding: com.flowglass.engine.trigger.TriggerBinding,
                reason: String,
                variables: Map<String, Any?>,
            ): Boolean = true
        }
        val dispatcher = TriggerDispatcher(
            clock = Clock { 0 },
            environment = object : TriggerEnvironment {
                override fun zoneOffsetMinutes(atEpochMs: Long): Int = 0
                override fun batteryPercent(): Int? = null
                override fun isCharging(): Boolean = false
                override fun isScreenOn(): Boolean = true
                override fun networkType(): NetworkType = NetworkType.NONE
            },
            executor = executor,
            skipRecorder = TriggerSkipRecorder { _, _, _, _ -> },
            onAccess = { name ->
                seen[name] = Looper.myLooper() == mainLooper
                done.countDown()
            },
        )
        val worker = TriggerEventWorker(scope, handler = {})
        worker.start()
        worker.submit {
            dispatcher.dispatch(
                TriggerEvent(TriggerKinds.SCREEN_ON, TriggerSource.SCREEN, 0),
                emptyList(),
            )
        }
        worker.submit { dispatcher.prune(emptyList()) }
        worker.submit { dispatcher.onRunFinished(TriggerRule("r", 1L, "r", true)) }
        worker.submit { dispatcher.reset() }

        assertEquals("dispatcher lane stalled", true, done.await(3, TimeUnit.SECONDS))
        assertEquals(setOf("dispatch", "prune", "onRunFinished", "reset"), seen.keys)
        seen.forEach { (name, onMain) ->
            assertFalse("$name ran on the main thread", onMain)
        }
        worker.stop()
    }
}
