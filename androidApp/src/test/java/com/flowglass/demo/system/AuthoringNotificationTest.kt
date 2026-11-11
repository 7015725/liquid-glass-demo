package com.flowglass.demo.system

import android.app.Notification
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthoringNotificationTest {
    @Test fun foregroundNotificationStatesAuthoringBoundaryInsteadOfClaimingToListen() {
        val controller = Robolectric.buildService(KeepAliveService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(service, KeepAliveService::class.java), 0, 1)
            val notification = shadowOf(service).lastForegroundNotification
            assertNotNull("The real service must publish its foreground notification", notification)
            assertTrue("The test must exercise successful foreground startup", KeepAlive.serviceRunning)
            assertEquals("仅编写、校验和保存，不监听或执行指令", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals("流光自动化 · 指令编写器", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        } finally {
            controller.destroy()
        }
    }
}
