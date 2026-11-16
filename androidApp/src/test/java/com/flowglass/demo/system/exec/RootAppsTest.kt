package com.flowglass.demo.system.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootAppsTest {
    @Test fun parsesUsersWithoutHardcodingVendorIds() {
        val output = """
            Users:
                UserInfo{0:Owner:c13} running
                UserInfo{10:Work profile:30} running
                UserInfo{999:XSpace:404} running
        """.trimIndent()
        assertEquals(listOf(0 to "Owner", 10 to "Work profile", 999 to "XSpace"),
            RootApps.parseUsers(output).map { it.id to it.name })
    }

    @Test fun parsesOnlyValidPackageNames() {
        val output = """
            package:com.example.one
            package:com.example.two
            package:bad-name
            package:com.example.one
        """.trimIndent()
        assertEquals(listOf("com.example.one", "com.example.two"), RootApps.parsePackages(output))
    }

    @Test fun extractsComponentFromResolveActivityOutput() {
        assertEquals("com.example/.Main", RootApps.resolveComponent(
            "priority=0 preferredOrder=0\ncom.example/.Main",
        ))
        assertEquals("com.example/.Main", RootApps.resolveComponent(
            "priority=0 component=com.example/.Main",
        ))
        assertNull(RootApps.resolveComponent("no component here"))
    }
}
