package com.flowglass.demo.system

import android.content.Intent
import android.os.Looper
import com.flowglass.demo.xposed.SystemHookFamily
import com.flowglass.demo.xposed.SystemHookProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * TRG-005：A→B→A 的末态与两秒前的 A 内容相同，会被内容级限流挡掉；持久化值是 A、钩子却停在 B。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HookConfigRetryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        SystemHookSettings.resetBroadcastThrottle()
        SystemHookSettings.clock = System::currentTimeMillis
    }

    private fun configIntents(families: Int): List<Intent> =
        Shadows.shadowOf(app).broadcastIntents.filter {
            it.action == SystemHookProtocol.ACTION_CONFIG &&
                it.getIntExtra(SystemHookProtocol.EXTRA_FAMILIES, -1) == families
        }

    @Test
    fun anAtoBtoASequenceStillDeliversTheFinalA() {
        SystemHookSettings.resetBroadcastThrottle()
        var now = 1_000L
        SystemHookSettings.clock = { now }
        val a = SystemHookFamily.FOREGROUND.mask
        val b = SystemHookFamily.PROCESS.mask

        SystemHookSettings.broadcastConfig(app, a, emptyList())
        SystemHookSettings.broadcastConfig(app, b, emptyList())
        // 第三次回到 A：与第一次同 key，会被限流，但必须安排补发。
        SystemHookSettings.broadcastConfig(app, a, emptyList())
        val immediate = configIntents(a).size

        now += 3_000
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)

        assertTrue("末态 A 必须最终送达钩子", configIntents(a).size > immediate)
        assertEquals(a, SystemHookSettings.subscribedFamilies(app))
    }

    @Test
    fun clearingWhileThrottledStillDeliversTheEmptyConfig() {
        SystemHookSettings.resetBroadcastThrottle()
        var now = 1_000L
        SystemHookSettings.clock = { now }
        SystemHookSettings.clearSubscriptions(app)
        SystemHookSettings.broadcastConfig(app, SystemHookFamily.FOREGROUND.mask, emptyList())
        // 第三次是清空：与第一次的 0 同 key，被限流，必须补发。
        SystemHookSettings.clearSubscriptions(app)
        val immediate = configIntents(0).size

        now += 3_000
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)

        assertTrue("关闭订阅也必须最终送达", configIntents(0).size > immediate)
        assertEquals(0, SystemHookSettings.subscribedFamilies(app))
    }
}
