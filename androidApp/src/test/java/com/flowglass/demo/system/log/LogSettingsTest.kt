package com.flowglass.demo.system.log

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.flowglass.engine.log.TraceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun defaultsMatchTheConfirmedPolicy() {
        val settings = LogSettings(context)
        assertEquals(true, settings.enabled)
        assertEquals(TraceLevel.INFO, settings.level)
        assertEquals(7, settings.retentionDays)
        assertEquals(50_000, settings.maxRows)
        assertEquals(true, settings.includeRawEvents)
    }

    @Test
    fun valuesAreClampedAndLevelsAreOrdered() {
        val settings = LogSettings(context)
        settings.retentionDays = 0
        settings.maxRows = 1
        assertEquals(1, settings.retentionDays)
        assertEquals(1_000, settings.maxRows)

        settings.level = TraceLevel.ERROR
        assertTrue(settings.accepts(TraceLevel.ERROR))
        assertFalse(settings.accepts(TraceLevel.INFO))

        settings.enabled = false
        assertFalse(settings.accepts(TraceLevel.ERROR))
    }
}
