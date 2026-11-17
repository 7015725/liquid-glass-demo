package com.flowglass.demo.system

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemHookSettingsTest {
    @Test
    fun heartbeatWithoutAnyInstalledHookIsNotReady() {
        assertEquals(
            SystemHookSettings.Status.NO_HOOKS,
            SystemHookSettings.status(enabled = true, heartbeatFresh = true, hookCount = 0),
        )
        assertEquals(
            SystemHookSettings.Status.NO_HOOKS,
            SystemHookSettings.status(enabled = true, heartbeatFresh = true, hookCount = -1),
        )
    }

    @Test
    fun readyRequiresHeartbeatAndInstalledHook() {
        assertEquals(
            SystemHookSettings.Status.READY,
            SystemHookSettings.status(enabled = true, heartbeatFresh = true, hookCount = 3),
        )
        assertEquals(
            SystemHookSettings.Status.WAITING,
            SystemHookSettings.status(enabled = true, heartbeatFresh = false, hookCount = 3),
        )
        assertEquals(
            SystemHookSettings.Status.DISABLED,
            SystemHookSettings.status(enabled = false, heartbeatFresh = true, hookCount = 3),
        )
    }
}
