package com.flowglass.demo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorTokensTest {
    @Test fun approvedPrimaryAndMutedAreReadableOnTheirSurfaces() {
        assertEquals(Color(0xFF0F62E0), C.Primary)
        assertEquals(Color(0xFF6A6A76), C.Muted)
        assertContrast(Color.White, C.Primary)
        assertContrast(C.Muted, C.Card)
        assertContrast(C.Muted, C.Bg)
    }

    @Test fun existingBackgroundAndGraphicColorsAreUnchanged() {
        assertEquals(Color(0xFFEFEEF2), C.Bg)
        assertEquals(Color(0xFFFAF8FA), C.Card)
        assertEquals(Color(0xFFEAE8EE), C.Tonal)
        assertEquals(Color(0xFFD6E7FF), C.PrimarySoft)
        assertEquals(Color(0xFF17A34A), C.Good)
        assertEquals(Color(0xFFDDF5E4), C.GoodSoft)
        assertEquals(Color(0xFF0F6B32), C.GoodText)
        assertEquals(Color(0xFFE5484D), C.Bad)
    }

    @Test fun warningAndErrorTextUseReadableDedicatedColors() {
        assertEquals(Color(0xFFA1231B), C.BadText)
        assertEquals(Color(0xFF8A4500), C.Warn)
        assertEquals(Color(0xFFFFF1DF), C.WarnSoft)
        listOf(C.Card, C.Bg, C.Tonal, C.PrimarySoft).forEach { assertContrast(C.BadText, it) }
        assertContrast(C.Warn, C.WarnSoft)
        assertContrast(C.OnSurfaceVar, C.Tonal)
    }

    private fun assertContrast(foreground: Color, background: Color) {
        val light = maxOf(foreground.luminance(), background.luminance())
        val dark = minOf(foreground.luminance(), background.luminance())
        val ratio = (light + 0.05f) / (dark + 0.05f)
        assertTrue("Text contrast $ratio is below 4.5:1", ratio >= 4.5f)
    }
}
