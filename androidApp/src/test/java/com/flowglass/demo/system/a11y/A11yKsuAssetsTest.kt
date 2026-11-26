package com.flowglass.demo.system.a11y

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11yKsuAssetsTest {

    private val app: Context = RuntimeEnvironment.getApplication()

    private fun asset(name: String): String =
        app.assets.open("a11y-keepalive/$name").bufferedReader().use { it.readText() }

    @Test
    fun `module prop declares the module id`() {
        val text = asset("module.prop")
        assertTrue(text.contains("id=flowglass_a11y_keepalive"))
        assertTrue(text.contains("version=v1"))
    }

    @Test
    fun `service script is posix sh and guards user intent`() {
        val text = asset("service.sh")
        assertTrue(text.startsWith("#!/system/bin/sh"))
        assertTrue(text.contains("INTERVAL=30"))
        assertTrue(text.contains("pidof"))
        assertTrue(text.contains("stopped=true"))
        assertTrue(text.contains("enabled_accessibility_services"))
    }
}
