package com.flowglass.demo.system.exec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JavaCodeCrashMarkerTest {
    @Test
    fun markerSurvivesUntilConsumedExactlyOnce() {
        val context = RuntimeEnvironment.getApplication()
        JavaCodeCrashMarker.markRunning(context)
        assertTrue(JavaCodeCrashMarker.consumeCrash(context))
        assertFalse(JavaCodeCrashMarker.consumeCrash(context))
    }
}
