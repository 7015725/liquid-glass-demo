package com.flowglass.demo.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootGrantsTest {
    private val specs = RootGrants.specs(
        packageName = "com.flowglass.demo",
        accessibilityComponent = "com.flowglass.demo/.system.FlowAccessibilityService",
        listenerComponent = "com.flowglass.demo/.system.trigger.FlowNotificationListener",
        sdk = 35,
    )

    @Test fun everyRootGrantUsesAFixedPackageScopedCommand() {
        assertTrue(specs.any { it.key == "write_settings" })
        assertTrue(specs.any { it.key == "dnd" })
        assertTrue(specs.any { it.key == "a11y" })
        assertTrue(specs.any { it.key == "usage" })
        assertTrue(specs.any { it.key == "alarm" })
        assertTrue(specs.any { it.key == "overlay" })
        specs.flatMap { it.commands }.forEach { command ->
            assertTrue(command, command.contains("com.flowglass.demo") || command.contains("FlowAccessibility"))
            assertTrue(
                command,
                command.startsWith("appops ") || command.startsWith("pm grant ") ||
                    command.startsWith("settings ") || command.startsWith("cmd notification ") ||
                    command.startsWith("dumpsys ") || command.startsWith("cur="),
            )
        }
    }

    @Test fun accessibilityCommandAppendsWithoutDroppingExistingServices() {
        val command = specs.first { it.key == "a11y" }.commands.single()
        assertTrue(command.contains("case \":\$cur:\""))
        assertTrue(command.contains("enabled_accessibility_services"))
        assertTrue(command.contains("FlowAccessibilityService"))
    }

    @Test fun everySpecHasAUniqueKeyAndANonEmptyCommandList() {
        assertEquals(specs.map { it.key }.distinct().size, specs.size)
        assertTrue(specs.all { it.commands.isNotEmpty() })
    }

    @Test fun olderSystemsDoNotRunGrantsForPermissionsTheyDoNotHave() {
        val old = RootGrants.specs("com.flowglass.demo", "com.flowglass.demo/.A", "com.flowglass.demo/.B", 30)
        assertTrue(old.none { it.key == "notify" })
        assertTrue(old.none { it.key == "bluetooth_connect" })
        assertTrue(old.none { it.key == "alarm" })
    }
}
