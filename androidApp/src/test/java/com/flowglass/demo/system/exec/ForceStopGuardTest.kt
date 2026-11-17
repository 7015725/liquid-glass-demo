package com.flowglass.demo.system.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForceStopGuardTest {
    @Test
    fun parsesPackageAndOptionalUser() {
        assertEquals(
            ForceStopTarget("com.example.app", 0),
            ForceStopGuard.parse(" com.example.app "),
        )
        assertEquals(
            ForceStopTarget("com.example.app", 10),
            ForceStopGuard.parse("com.example.app@10"),
        )
        assertEquals(
            "am force-stop --user 10 com.example.app",
            ForceStopGuard.parse("com.example.app@10").command,
        )
    }

    @Test
    fun rejectsReservedLauncherAndMalformedTargets() {
        assertThrows(IllegalArgumentException::class.java) { ForceStopGuard.parse("") }
        assertThrows(IllegalArgumentException::class.java) { ForceStopGuard.parse("android") }
        assertThrows(IllegalArgumentException::class.java) { ForceStopGuard.parse("com.android.systemui") }
        assertThrows(IllegalArgumentException::class.java) { ForceStopGuard.parse("com.flowglass.demo") }
        assertThrows(IllegalArgumentException::class.java) {
            ForceStopGuard.parse("com.example.launcher", setOf("com.example.launcher"))
        }
        assertThrows(IllegalArgumentException::class.java) { ForceStopGuard.parse("com.example.app;rm -rf /") }
        assertThrows(IllegalArgumentException::class.java) { ForceStopGuard.parse("com.example.app@-1") }
        assertThrows(IllegalArgumentException::class.java) { ForceStopGuard.parse("com.example.app@1@2") }
        assertThrows(IllegalArgumentException::class.java) { ForceStopGuard.parse("com.example.app@abc") }
    }
}
