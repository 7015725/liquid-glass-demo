package com.flowglass.demo.system.a11y

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11yKeepAliveSettingsTest {

    private lateinit var app: Context

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("a11y_keepalive", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `disabled by default and no targets`() {
        assertFalse(A11yKeepAliveSettings.isEnabled(app))
        assertEquals(emptySet<String>(), A11yKeepAliveSettings.targets(app))
    }

    @Test
    fun `targets round trip and reject malformed components`() {
        A11yKeepAliveSettings.setTargets(
            app,
            setOf("com.a/com.a.A", "com.b/com.b.B", "junk", "com.c/com.c.C"),
        )
        assertEquals(setOf("com.a/com.a.A", "com.b/com.b.B", "com.c/com.c.C"), A11yKeepAliveSettings.targets(app))
    }

    @Test
    fun `enabling the switch selects this app when nothing was chosen`() {
        A11yKeepAliveSettings.setEnabled(
            app,
            true,
            selfComponent = "com.flowglass.demo/com.flowglass.demo.system.FlowAccessibilityService",
        )
        assertTrue(A11yKeepAliveSettings.isEnabled(app))
        assertEquals(
            setOf("com.flowglass.demo/com.flowglass.demo.system.FlowAccessibilityService"),
            A11yKeepAliveSettings.targets(app),
        )
    }

    @Test
    fun `enabling twice does not re-add a removed self target`() {
        A11yKeepAliveSettings.setEnabled(app, true, selfComponent = "com.a/com.a.A")
        A11yKeepAliveSettings.toggleTarget(app, "com.a/com.a.A")
        A11yKeepAliveSettings.setEnabled(app, true, selfComponent = "com.a/com.a.A")
        assertEquals(emptySet<String>(), A11yKeepAliveSettings.targets(app))
    }

    @Test
    fun `toggling a row before enabling keeps that row selected`() {
        A11yKeepAliveSettings.toggleTarget(app, "com.a/com.a.A")
        A11yKeepAliveSettings.setEnabled(app, true, selfComponent = "com.b/com.b.B", seed = false)
        assertTrue(A11yKeepAliveSettings.isEnabled(app))
        assertEquals(setOf("com.a/com.a.A"), A11yKeepAliveSettings.targets(app))
    }

    @Test
    fun `status encoding round trips and drops unknown states`() {
        val encoded = A11yKeepAliveSettings.encodeStatus(
            mapOf(
                "com.a/com.a.A" to A11yKeepAliveSettings.ComponentStatus(
                    A11yKeepAliveSettings.State.REPAIRED, "", 1_700_000_000_000L,
                ),
            ),
        )
        assertEquals(
            A11yKeepAliveSettings.ComponentStatus(
                A11yKeepAliveSettings.State.REPAIRED, "", 1_700_000_000_000L,
            ),
            A11yKeepAliveSettings.decodeStatus(encoded)["com.a/com.a.A"],
        )
        assertEquals(
            emptyMap<String, A11yKeepAliveSettings.ComponentStatus>(),
            A11yKeepAliveSettings.decodeStatus("x|bogus|r|1"),
        )
    }

    @Test
    fun `backend defaults to auto and can be forced`() {
        assertEquals(A11yKeepAliveSettings.BACKEND_AUTO, A11yKeepAliveSettings.backend(app))
        A11yKeepAliveSettings.setBackend(app, A11yKeepAliveSettings.BACKEND_ROOT)
        assertEquals(A11yKeepAliveSettings.BACKEND_ROOT, A11yKeepAliveSettings.backend(app))
    }
}
