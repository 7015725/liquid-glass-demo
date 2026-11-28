package com.flowglass.demo.system.clipboard

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @org.junit.Before
    fun clearPreferences() {
        context.getSharedPreferences(ClipboardSettings.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultsAreOnSevenDaysAndFiveThousandRows() {
        val settings = ClipboardSettings(context)
        assertTrue(settings.enabled)
        assertEquals(7, settings.retentionDays)
        assertEquals(5_000, settings.maxEntries)
    }

    @Test
    fun valuesPersistAndAreClamped() {
        val settings = ClipboardSettings(context)
        settings.enabled = false
        settings.retentionDays = 200
        settings.maxEntries = 10
        assertEquals(false, settings.enabled)
        assertEquals(90, settings.retentionDays)
        assertEquals(100, settings.maxEntries)
    }
}
