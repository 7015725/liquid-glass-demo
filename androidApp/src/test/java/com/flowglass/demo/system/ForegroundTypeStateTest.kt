package com.flowglass.demo.system

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTypeStateTest {
    @Test
    fun failedLocationUpgradeDoesNotDowngradeSpecialUse() {
        val state = ForegroundTypeState()
        assertEquals(ForegroundStartAction.SPECIAL_USE, state.next(includeLocation = false))
        state.onStarted(ForegroundStartAction.SPECIAL_USE)
        assertEquals(ForegroundStartAction.LOCATION, state.next(includeLocation = true))

        state.onLocationFailure()

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, state.currentTypes)
        assertFalse(state.hasLocation)
        assertTrue(state.currentTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)
    }

    @Test
    fun acceptedLocationTypeIsNeverReplacedByABackgroundStart() {
        val state = ForegroundTypeState()
        state.onStarted(ForegroundStartAction.LOCATION)

        assertEquals(ForegroundStartAction.NONE, state.next(includeLocation = false))
        assertEquals(ForegroundStartAction.NONE, state.next(includeLocation = true))
        state.onLocationFailure()

        assertTrue(state.hasLocation)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            state.currentTypes,
        )
    }
}
