package com.flowglass.demo.system

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemHookSettingsTest {
    @Test
    fun notLoadedIsDistinctFromLoadedButDisabled() {
        assertEquals(
            SystemHookSettings.Status.NOT_LOADED,
            SystemHookSettings.status(
                enabled = true,
                loaded = false,
                heartbeatFresh = false,
                hookCount = -1,
            ),
        )
        assertEquals(
            SystemHookSettings.Status.LOADED_DISABLED,
            SystemHookSettings.status(
                enabled = false,
                loaded = true,
                heartbeatFresh = false,
                hookCount = 3,
            ),
        )
    }

    @Test
    fun heartbeatWithoutAnyInstalledHookIsNotReady() {
        assertEquals(
            SystemHookSettings.Status.NO_HOOKS,
            SystemHookSettings.status(
                enabled = true,
                loaded = true,
                heartbeatFresh = true,
                hookCount = 0,
            ),
        )
        assertEquals(
            SystemHookSettings.Status.NO_HOOKS,
            SystemHookSettings.status(
                enabled = true,
                loaded = true,
                heartbeatFresh = true,
                hookCount = -1,
            ),
        )
    }

    @Test
    fun readyRequiresLoadedEnabledHeartbeatAndInstalledHook() {
        assertEquals(
            SystemHookSettings.Status.READY,
            SystemHookSettings.status(
                enabled = true,
                loaded = true,
                heartbeatFresh = true,
                hookCount = 3,
            ),
        )
        assertEquals(
            SystemHookSettings.Status.LOADED_WAITING,
            SystemHookSettings.status(
                enabled = true,
                loaded = true,
                heartbeatFresh = false,
                hookCount = 3,
            ),
        )
    }
}
