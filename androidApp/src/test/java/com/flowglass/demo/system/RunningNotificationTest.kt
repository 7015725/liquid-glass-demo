package com.flowglass.demo.system

import android.app.NotificationManager
import android.content.Context
import com.flowglass.demo.system.trigger.TriggerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Trigger listening and keep-alive are separate foreground services. Users must still see one
 * "正在运行" notification, not two identical ones, and the text must describe both states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunningNotificationTest {
    private fun posted(ctx: Context) =
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun bothServicesShareOnePostedNotification() {
        val ctx: Context = RuntimeEnvironment.getApplication()

        val trigger = Robolectric.buildService(TriggerService::class.java).create().get()
        val triggerNotification = shadowOf(trigger).lastForegroundNotification
        assertNotNull("监听服务必须发布常驻通知", triggerNotification)
        assertEquals(RunningNotification.ID, shadowOf(trigger).lastForegroundNotificationId)

        val keepAlive = Robolectric.buildService(KeepAliveService::class.java).create()
            .startCommand(0, 0).get()
        assertNotNull("保活服务必须发布常驻通知", shadowOf(keepAlive).lastForegroundNotification)
        assertEquals(RunningNotification.ID, shadowOf(keepAlive).lastForegroundNotificationId)

        assertEquals("两个服务只能共用一条系统通知", 1, posted(ctx).size)

        RunningNotification.onTriggerStatus(ctx, 5)
        val text = posted(ctx).singleOrNull()?.let {
            it.extras.getCharSequence("android.text")?.toString()
        }.orEmpty()
        assertTrue("通知应包含监听条数：$text", text.contains("正在监听 5 条自动指令"))
        assertTrue("通知应包含保活状态：$text", text.contains("后台保活已开启"))
    }

    @Test
    fun stoppingOneServiceKeepsTheOtherNotification() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val triggerController = Robolectric.buildService(TriggerService::class.java).create()
        val trigger = triggerController.get()
        val keepAliveController = Robolectric.buildService(KeepAliveService::class.java).create()
            .startCommand(0, 0)
        val keepAlive = keepAliveController.get()

        RunningNotification.onTriggerStatus(ctx, 3)
        triggerController.destroy()
        assertEquals("监听服务停止后保活服务仍需通知", 1, posted(ctx).size)

        keepAliveController.destroy()
        assertEquals("两个服务都停止后应取回通知", 0, posted(ctx).size)

        // No stale reference may keep the merged notification alive.
        RunningNotification.onTriggerStatus(ctx, 0)
        assertEquals(0, posted(ctx).size)
    }
}
