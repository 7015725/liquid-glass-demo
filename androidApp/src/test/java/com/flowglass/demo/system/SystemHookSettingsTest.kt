package com.flowglass.demo.system

import com.flowglass.demo.xposed.SystemHookFamily
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
    fun subscribedFamilyWithoutADetectionPointIsNotReady() {
        assertEquals(
            SystemHookSettings.Status.FAMILY_UNAVAILABLE,
            SystemHookSettings.status(
                enabled = true,
                loaded = true,
                heartbeatFresh = true,
                hookCount = 1,
                availableFamilies = SystemHookFamily.FOREGROUND.mask,
                subscribedFamilies = SystemHookFamily.FOREGROUND.mask or SystemHookFamily.PROCESS.mask,
            ),
        )
        assertEquals(
            SystemHookSettings.Status.READY,
            SystemHookSettings.status(
                enabled = true,
                loaded = true,
                heartbeatFresh = true,
                hookCount = 2,
                availableFamilies = SystemHookFamily.FOREGROUND.mask or SystemHookFamily.PROCESS.mask,
                subscribedFamilies = SystemHookFamily.FOREGROUND.mask or SystemHookFamily.PROCESS.mask,
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
