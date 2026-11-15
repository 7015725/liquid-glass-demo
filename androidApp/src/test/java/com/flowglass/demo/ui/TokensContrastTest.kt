package com.flowglass.demo.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast gates for the semantic palette. These are deliberately plain JVM tests:
 * changing a token without rechecking the two themes fails CI immediately.
 */
class TokensContrastTest {
    private fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(foreground: Color, background: Color): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun assertNormalText(palette: Palette, name: String, fg: Color, bg: Color) {
        val ratio = contrast(fg, bg)
        assertTrue(
            "${palette.name} $name contrast $ratio < 4.5",
            ratio >= 4.5,
        )
    }

    private fun checkTextPairs(palette: Palette) {
        assertNormalText(palette, "onSurface/bg", palette.onSurface, palette.bg)
        assertNormalText(palette, "onSurface/card", palette.onSurface, palette.card)
        assertNormalText(palette, "onSurface/tonal", palette.onSurface, palette.tonal)
        assertNormalText(palette, "onSurfaceVar/bg", palette.onSurfaceVar, palette.bg)
        assertNormalText(palette, "onSurfaceVar/card", palette.onSurfaceVar, palette.card)
        assertNormalText(palette, "muted/bg", palette.muted, palette.bg)
        assertNormalText(palette, "muted/card", palette.muted, palette.card)
        assertNormalText(palette, "onPrimary/primary", palette.onPrimary, palette.primary)
        assertNormalText(palette, "primary/primarySoft", palette.primary, palette.primarySoft)
        assertNormalText(palette, "goodText/goodSoft", palette.goodText, palette.goodSoft)
        assertNormalText(palette, "warnText/warnSoft", palette.warnText, palette.warnSoft)
        assertNormalText(palette, "bad/badSoft", palette.bad, palette.badSoft)
        assertNormalText(palette, "inverseOnSurface/inverseSurface", palette.inverseOnSurface, palette.inverseSurface)
        assertNormalText(palette, "onSurface/glassBase", palette.onSurface, palette.glassBase)
        assertNormalText(palette, "muted/glassBase", palette.muted, palette.glassBase)
    }

    @Test
    fun lightThemeTextMeetsWcagAa() = checkTextPairs(LightPalette)

    @Test
    fun darkThemeTextMeetsWcagAa() = checkTextPairs(DarkPalette)

    private fun over(foreground: Color, background: Color): Color {
        val a = foreground.alpha
        return Color(
            red = foreground.red * a + background.red * (1f - a),
            green = foreground.green * a + background.green * (1f - a),
            blue = foreground.blue * a + background.blue * (1f - a),
            alpha = 1f,
        )
    }

    @Test
    fun iconAndLargeTextPairsMeetThreeToOne() {
        listOf(LightPalette, DarkPalette).forEach { palette ->
            val navSurface = over(palette.navSurface, palette.bg)
            assertTrue("${palette.name} primary/bg", contrast(palette.primary, palette.bg) >= 3.0)
            assertTrue("${palette.name} primary/card", contrast(palette.primary, palette.card) >= 3.0)
            assertTrue("${palette.name} navAccent/navSurface", contrast(palette.navAccent, navSurface) >= 3.0)
        }
    }
}
