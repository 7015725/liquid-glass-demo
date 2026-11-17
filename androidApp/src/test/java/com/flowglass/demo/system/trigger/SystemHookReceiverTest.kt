package com.flowglass.demo.system.trigger

import android.content.Context
import android.content.Intent
import com.flowglass.demo.xposed.SystemHookProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemHookReceiverTest {
    private fun app() = RuntimeEnvironment.getApplication().also {
        it.getSharedPreferences("system_hook", Context.MODE_PRIVATE).edit().clear().commit()
        SystemEventCache.clear()
    }

    @Test
    fun genericForegroundEventStartsTheLegacyForegroundAction() {
        val app = app()
        SystemHookReceiver().onReceive(
            app,
            Intent(SystemHookProtocol.ACTION_EVENT)
                .putExtra(SystemHookProtocol.EXTRA_EVENT, SystemHookProtocol.EVENT_FOREGROUND)
                .putExtra(SystemHookProtocol.EXTRA_PACKAGE, "com.example.app")
                .putExtra(SystemHookProtocol.EXTRA_USER, 10),
        )

        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull("foreground event did not start TriggerService", started)
        assertEquals(TriggerService.ACTION_SYSTEM_FOREGROUND, started.action)
        assertEquals("com.example.app", started.getStringExtra(SystemHookProtocol.EXTRA_PACKAGE))
        assertEquals(10, started.getIntExtra(SystemHookProtocol.EXTRA_USER, -1))
    }

    @Test
    fun processEventUpdatesTheCacheAndStartsTheSystemEventAction() {
        val app = app()
        SystemHookReceiver().onReceive(
            app,
            Intent(SystemHookProtocol.ACTION_EVENT)
                .putExtra(SystemHookProtocol.EXTRA_EVENT, SystemHookProtocol.EVENT_PROCESS_STARTED)
                .putExtra(SystemHookProtocol.EXTRA_PACKAGE, "com.example.app")
                .putExtra(SystemHookProtocol.EXTRA_USER, 10),
        )

        assertTrue(SystemEventCache.isProcessRunning("com.example.app", 10) == true)
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(TriggerService.ACTION_SYSTEM_EVENT, started.action)
        assertEquals(SystemHookProtocol.EVENT_PROCESS_STARTED, started.getStringExtra(SystemHookProtocol.EXTRA_EVENT))
    }

    @Test
    fun malformedEventIsDroppedBeforeStartingTheService() {
        val app = app()
        SystemHookReceiver().onReceive(
            app,
            Intent(SystemHookProtocol.ACTION_EVENT)
                .putExtra(SystemHookProtocol.EXTRA_EVENT, SystemHookProtocol.EVENT_PROCESS_STARTED)
                .putExtra(SystemHookProtocol.EXTRA_PACKAGE, "not a package")
                .putExtra(SystemHookProtocol.EXTRA_USER, 10),
        )

        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
        assertEquals(null, SystemEventCache.isProcessRunning("not a package", 10))
    }
}
