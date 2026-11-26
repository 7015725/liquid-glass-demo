package com.flowglass.demo.system.trigger

import android.content.Context
import android.content.Intent
import com.flowglass.demo.system.a11y.A11yKeepAliveSettings
import com.flowglass.demo.xposed.a11y.A11yKeepAliveProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11yKeepAliveReceiverTest {

    private lateinit var app: Context

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("a11y_keepalive", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `loaded records support and answers with config`() {
        A11yKeepAliveReceiver().onReceive(
            app,
            Intent(A11yKeepAliveProtocol.ACTION_LOADED)
                .putExtra(A11yKeepAliveProtocol.EXTRA_HOOKS, 3)
                .putExtra(A11yKeepAliveProtocol.EXTRA_SUPPORTED, true),
        )
        assertTrue(A11yKeepAliveSettings.isHookLoaded(app))
        assertTrue(A11yKeepAliveSettings.isHookSupported(app))
        assertEquals(3, A11yKeepAliveSettings.hookCount(app))
    }

    @Test
    fun `unknown status is dropped`() {
        A11yKeepAliveReceiver().onReceive(
            app,
            Intent(A11yKeepAliveProtocol.ACTION_STATUS)
                .putExtra(A11yKeepAliveProtocol.EXTRA_COMPONENT, "com.a/com.a.A")
                .putExtra(A11yKeepAliveProtocol.EXTRA_STATE, "bogus"),
        )
        assertEquals(
            emptyMap<String, A11yKeepAliveSettings.ComponentStatus>(),
            A11yKeepAliveSettings.statuses(app),
        )
    }

    @Test
    fun `repaired bumps the counter`() {
        A11yKeepAliveReceiver().onReceive(
            app,
            Intent(A11yKeepAliveProtocol.ACTION_STATUS)
                .putExtra(A11yKeepAliveProtocol.EXTRA_COMPONENT, "com.a/com.a.A")
                .putExtra(A11yKeepAliveProtocol.EXTRA_STATE, "repaired")
                .putExtra(A11yKeepAliveProtocol.EXTRA_AT, 1L),
        )
        assertEquals(1, A11yKeepAliveSettings.restoredCount(app))
    }
}
