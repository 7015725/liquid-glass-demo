package com.flowglass.demo.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearanceSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsToSystem() {
        assertEquals(ThemeMode.SYSTEM, AppearanceSettings.read(context))
    }

    @Test
    fun persistsEveryExplicitMode() {
        AppearanceSettings.write(context, ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, AppearanceSettings.read(context))

        AppearanceSettings.write(context, ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, AppearanceSettings.read(context))

        AppearanceSettings.write(context, ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, AppearanceSettings.read(context))
    }

    @Test
    fun unknownStoredValueFallsBackToSystem() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", "future-mode").commit()
        assertEquals(ThemeMode.SYSTEM, AppearanceSettings.read(context))
    }
}
