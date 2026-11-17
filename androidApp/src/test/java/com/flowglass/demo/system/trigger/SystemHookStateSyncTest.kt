package com.flowglass.demo.system.trigger

import android.content.Context
import android.content.Intent
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.xposed.SystemHookFamily
import com.flowglass.demo.xposed.SystemHookProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemHookStateSyncTest {
    @Test
    fun appStartedBeforeHookEventuallySynchronizesEnabledState() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("system_hook", Context.MODE_PRIVATE).edit().clear().commit()

        // App process is already alive and has persisted the user's switch, but the hook has not
        // registered its receiver yet. This is the reboot race: FlowApp's broadcast was lost.
        SystemHookSettings.setEnabled(app, true)
        assertEquals(SystemHookSettings.Status.NOT_LOADED, SystemHookSettings.status(app))

        // The hook registers later and announces itself. The app must keep enabled=true and
        // immediately reply with that value plus the acknowledgement flag.
        SystemHookReceiver().onReceive(
            app,
            Intent(SystemHookProtocol.ACTION_LOADED)
                .putExtra(SystemHookProtocol.EXTRA_HOOKS, 3),
        )

        assertTrue(SystemHookSettings.isLoaded(app))
        assertTrue(SystemHookSettings.isEnabled(app))
        assertEquals(SystemHookSettings.Status.LOADED_WAITING, SystemHookSettings.status(app))

        val reply = Shadows.shadowOf(app).broadcastIntents.last { it.action == SystemHookProtocol.ACTION_STATE }
        assertTrue(reply.getBooleanExtra(SystemHookProtocol.EXTRA_ENABLED, false))
        assertTrue(reply.getBooleanExtra(SystemHookProtocol.EXTRA_APP_KNOWS_LOADED, false))

        SystemHookSettings.markHeartbeat(app, 3)
        assertEquals(SystemHookSettings.Status.READY, SystemHookSettings.status(app))
    }

    @Test
    fun configBroadcastCarriesOnlyKnownFamiliesAndValidatedIdentities() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("system_hook", Context.MODE_PRIVATE).edit().clear().commit()
        SystemHookSettings.broadcastConfig(
            app,
            SystemHookFamily.FOREGROUND.mask or SystemHookFamily.PROCESS.mask or 0x4000,
            listOf("com.example.app@10", "not a package", "com.other"),
        )

        val config = Shadows.shadowOf(app).broadcastIntents.last { it.action == SystemHookProtocol.ACTION_CONFIG }
        assertEquals(
            SystemHookFamily.FOREGROUND.mask or SystemHookFamily.PROCESS.mask,
            config.getIntExtra(SystemHookProtocol.EXTRA_FAMILIES, -1),
        )
        val watched = config.getStringExtra(SystemHookProtocol.EXTRA_WATCHED).orEmpty().split(',').toSet()
        assertEquals(setOf("com.example.app@10", "com.other"), watched)
    }

    @Test
    fun loadedHookWithSwitchOffRepliesDisabled() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("system_hook", Context.MODE_PRIVATE).edit().clear().commit()

        SystemHookSettings.setEnabled(app, false)
        SystemHookReceiver().onReceive(
            app,
            Intent(SystemHookProtocol.ACTION_LOADED)
                .putExtra(SystemHookProtocol.EXTRA_HOOKS, 3),
        )

        assertTrue(SystemHookSettings.isLoaded(app))
        assertFalse(SystemHookSettings.isEnabled(app))
        assertEquals(SystemHookSettings.Status.LOADED_DISABLED, SystemHookSettings.status(app))

        val reply = Shadows.shadowOf(app).broadcastIntents.last { it.action == SystemHookProtocol.ACTION_STATE }
        assertFalse(reply.getBooleanExtra(SystemHookProtocol.EXTRA_ENABLED, true))
        assertTrue(reply.getBooleanExtra(SystemHookProtocol.EXTRA_APP_KNOWS_LOADED, false))
    }
}
