package com.flowglass.demo.xposed

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleStatusModernTest {
    @After
    fun reset() {
        ModuleStatus.clearModern()
    }

    @Test
    fun serviceMetadataMarksTheModuleActive() {
        assertFalse(ModuleStatus.isActive())
        ModuleStatus.markModern(
            apiVersion = 101,
            frameworkName = "LSPosed",
            frameworkVersion = "2.0.0",
            frameworkVersionCode = 20000L,
            scope = listOf("system", "com.flowglass.demo", "com.example.target"),
        )
        assertTrue(ModuleStatus.isActive())
        assertTrue(ModuleStatus.isModern())
        assertEquals(101, ModuleStatus.frameworkVersion())
        assertEquals("LSPosed", ModuleStatus.frameworkName())
        assertEquals("2.0.0", ModuleStatus.frameworkVersionName())
        assertEquals(20000L, ModuleStatus.frameworkVersionCode())
        assertEquals(listOf("system", "com.flowglass.demo", "com.example.target"), ModuleStatus.scope())
    }

    @Test
    fun deadServiceClearsActivationAndScope() {
        ModuleStatus.markModern(101, "LSPosed", "2.0.0", 20000L, listOf("system"))
        ModuleStatus.clearModern()
        assertFalse(ModuleStatus.isActive())
        assertFalse(ModuleStatus.isModern())
        assertEquals(0, ModuleStatus.frameworkVersion())
        assertTrue(ModuleStatus.scope().isEmpty())
    }
}
