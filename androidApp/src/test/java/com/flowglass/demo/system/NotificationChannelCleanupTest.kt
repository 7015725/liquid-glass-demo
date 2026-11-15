package com.flowglass.demo.system

import android.app.NotificationChannel
import android.app.NotificationManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationChannelCleanupTest {
    @Test
    fun startupDeletesLegacyChannelsAndKeepsRunning() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf("keepalive", "triggers", "running").forEach { id ->
            manager.createNotificationChannel(NotificationChannel(id, id, NotificationManager.IMPORTANCE_LOW))
        }
        RunningNotification.cleanupLegacyChannels(context)
        assertNull(manager.getNotificationChannel("keepalive"))
        assertNull(manager.getNotificationChannel("triggers"))
        assertNotNull(manager.getNotificationChannel("running"))
    }
}
