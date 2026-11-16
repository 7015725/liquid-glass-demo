package com.flowglass.demo.system.exec

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchTargetTest {
    @Test fun barePackageKeepsLegacyCurrentProfileForm() {
        assertEquals("com.example" to null, LaunchableApps.parseLaunchTarget("com.example"))
    }

    @Test fun profileSuffixCarriesUserId() {
        assertEquals("com.example" to 10, LaunchableApps.parseLaunchTarget("com.example@10"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedSuffixIsRejectedInsteadOfFallingBackToTheMainApp() {
        LaunchableApps.parseLaunchTarget("com.example@work")
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyTargetIsRejected() {
        LaunchableApps.parseLaunchTarget("   ")
    }
}
