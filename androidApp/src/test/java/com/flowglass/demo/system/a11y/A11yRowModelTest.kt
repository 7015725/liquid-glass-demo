package com.flowglass.demo.system.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yRowModelTest {

    private fun entry(component: String, pkg: String, self: Boolean = false, enabled: Boolean = true) =
        A11yServiceEntry(
            component = component,
            packageName = pkg,
            className = component.substringAfter('/'),
            serviceLabel = "服务",
            appLabel = pkg,
            enabled = enabled,
            isSelf = self,
        )

    @Test
    fun `selected rows keep self first and expose the checked state`() {
        val rows = A11yRowModel.build(
            entries = listOf(
                entry("com.b/com.b.B", "com.b"),
                entry("com.flowglass.demo/com.flowglass.demo.system.FlowAccessibilityService", "com.flowglass.demo", self = true),
            ),
            targets = setOf("com.b/com.b.B"),
            statuses = emptyMap(),
        )
        assertTrue(rows.first().title.startsWith("流光自动化"))
        assertFalse(rows.first().selected)
        assertTrue(rows.last().selected)
    }

    @Test
    fun `status text follows the reported state`() {
        val rows = A11yRowModel.build(
            entries = listOf(entry("com.a/com.a.A", "com.a")),
            targets = setOf("com.a/com.a.A"),
            statuses = mapOf(
                "com.a/com.a.A" to A11yKeepAliveSettings.ComponentStatus(
                    A11yKeepAliveSettings.State.REPAIRED, "", 1L,
                ),
            ),
        )
        assertEquals("已恢复", rows.single().stateText)
        assertTrue(rows.single().ok)
    }

    @Test
    fun `disabled services explain that keep-alive waits`() {
        val rows = A11yRowModel.build(
            entries = listOf(entry("com.a/com.a.A", "com.a", enabled = false)),
            targets = setOf("com.a/com.a.A"),
            statuses = emptyMap(),
        )
        assertTrue(rows.single().subtitle.contains("未启用"))
        assertEquals("等待开启", rows.single().stateText)
    }
}
