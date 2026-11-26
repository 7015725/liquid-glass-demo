package com.flowglass.demo.system.a11y

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11yServiceCatalogTest {

    private fun raw(pkg: String, cls: String, service: String, app: String) =
        RawA11yService(packageName = pkg, className = cls, serviceLabel = service, appLabel = app)

    @Test
    fun `self service sorts first and is marked`() {
        val merged = A11yServiceCatalog.merge(
            installed = listOf(
                raw("com.other", "com.other.B", "其他服务", "其他应用"),
                raw("com.flowglass.demo", "com.flowglass.demo.system.FlowAccessibilityService", "流光", "流光自动化"),
            ),
            enabled = emptySet(),
            selfPackage = "com.flowglass.demo",
        )
        assertEquals("com.flowglass.demo", merged.first().packageName)
        assertTrue(merged.first().isSelf)
        assertEquals("com.other/com.other.B", merged.last().component)
    }

    @Test
    fun `enabled component without an installed entry is still listed`() {
        val merged = A11yServiceCatalog.merge(
            installed = emptyList(),
            enabled = setOf("com.gone/com.gone.Service"),
            selfPackage = "com.flowglass.demo",
        )
        assertEquals(1, merged.size)
        assertEquals("com.gone/com.gone.Service", merged.single().component)
        assertTrue(merged.single().enabled)
    }

    @Test
    fun `enabled list parsing normalises short class names and drops junk`() {
        assertEquals(
            setOf("com.a/com.a.A", "com.b/com.b.B"),
            A11yServiceCatalog.parseEnabledComponents("com.a/.A:com.b/com.b.B:not-a-component:"),
        )
        assertEquals(emptySet<String>(), A11yServiceCatalog.parseEnabledComponents(null))
        assertEquals(emptySet<String>(), A11yServiceCatalog.parseEnabledComponents(""))
    }

    @Test
    fun `installed entry matches an enabled value written in short form`() {
        val merged = A11yServiceCatalog.merge(
            installed = listOf(raw("com.a", "com.a.A", "服务", "应用")),
            enabled = A11yServiceCatalog.parseEnabledComponents("com.a/.A"),
            selfPackage = "com.flowglass.demo",
        )
        assertTrue(merged.single().enabled)
        assertEquals("com.a/com.a.A", merged.single().component)
    }

    @Test
    fun `installed entry with a relative class name still matches`() {
        // The manifest declares ".system.FlowAccessibilityService"; the framework may persist
        // either spelling, and both must resolve to the same key.
        val merged = A11yServiceCatalog.merge(
            installed = listOf(
                raw("com.flowglass.demo", ".system.FlowAccessibilityService", "流光", "流光自动化"),
            ),
            enabled = A11yServiceCatalog.parseEnabledComponents(
                "com.flowglass.demo/com.flowglass.demo.system.FlowAccessibilityService",
            ),
            selfPackage = "com.flowglass.demo",
        )
        assertEquals("com.flowglass.demo/com.flowglass.demo.system.FlowAccessibilityService", merged.single().component)
        assertTrue(merged.single().enabled)
    }

    @Test
    fun `settings reader returns enabled components and master switch`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.a/.A:com.b/com.b.B",
        )
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

        assertEquals(
            setOf("com.a/com.a.A", "com.b/com.b.B"),
            A11yServiceCatalog.enabledComponents(app),
        )
        assertTrue(A11yServiceCatalog.masterEnabled(app))
    }
}
