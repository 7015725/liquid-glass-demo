package com.flowglass.demo.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object C {
    /** Kept observable so every component reading a token is invalidated by a theme switch. */
    var dark by mutableStateOf(false)

    val palette: Palette get() = if (dark) DarkPalette else LightPalette

    val Bg get() = palette.bg
    val Card get() = palette.card
    val Tonal get() = palette.tonal
    val TonalStrong get() = palette.tonalStrong
    val Primary get() = palette.primary
    val PrimarySoft get() = palette.primarySoft
    val OnSurface get() = palette.onSurface
    val OnSurfaceVar get() = palette.onSurfaceVar
    val Muted get() = palette.muted
    val Divider get() = palette.divider
    val Good get() = palette.good
    val GoodSoft get() = palette.goodSoft
    val GoodText get() = palette.goodText
    val Mid get() = palette.mid
    val MidSoft get() = palette.midSoft
    val Bad get() = palette.bad
    val BadSoft get() = palette.badSoft
    val WarnSoft get() = palette.warnSoft
    val WarnText get() = palette.warnText
    val OnPrimary get() = palette.onPrimary
    val OnGood get() = palette.onGood
    val OnBad get() = palette.onBad
    val InverseSurface get() = palette.inverseSurface
    val InverseOnSurface get() = palette.inverseOnSurface
    val InversePrimary get() = palette.inversePrimary
    val Glass get() = palette.glass
    val GlassStrong get() = palette.glassStrong
    val GlassBase get() = palette.glassBase
    val Field get() = palette.field
    val Scrim get() = palette.scrim
    val ScrimSubtle get() = palette.scrimSubtle
    val NavSurface get() = palette.navSurface
    val NavAccent get() = palette.navAccent
    val NavSelection get() = palette.navSelection
    val NavPress get() = palette.navPress
    val NavShadow get() = palette.navShadow
    val ToggleTrack get() = palette.toggleTrack
    val ToggleThumb get() = palette.toggleThumb
    val ToggleShadow get() = palette.toggleShadow
    val Highlight get() = palette.highlight
    val Shimmer get() = palette.shimmer
    val IconCutout get() = palette.iconCutout
}

/** Semantic colors. Keep component code on these names rather than raw hex values. */
data class Palette(
    val name: String,
    val bg: Color,
    val card: Color,
    val tonal: Color,
    val tonalStrong: Color,
    val primary: Color,
    val primarySoft: Color,
    val onSurface: Color,
    val onSurfaceVar: Color,
    val muted: Color,
    val divider: Color,
    val good: Color,
    val goodSoft: Color,
    val goodText: Color,
    val mid: Color,
    val midSoft: Color,
    val bad: Color,
    val badSoft: Color,
    val warnSoft: Color,
    val warnText: Color,
    val onPrimary: Color,
    val onGood: Color,
    val onBad: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBase: Color,
    val field: Color,
    val scrim: Color,
    val scrimSubtle: Color,
    val navSurface: Color,
    val navAccent: Color,
    val navSelection: Color,
    val navPress: Color,
    val navShadow: Color,
    val toggleTrack: Color,
    val toggleThumb: Color,
    val toggleShadow: Color,
    val highlight: Color,
    val shimmer: Color,
    val iconCutout: Color,
)

val LightPalette = Palette(
    name = "浅色",
    bg = Color(0xFFEFEEF2),
    card = Color(0xFFFAF8FA),
    tonal = Color(0xFFEAE8EE),
    tonalStrong = Color(0xFFDEDCE3),
    primary = Color(0xFF0965CC),
    // Slightly lighter than the old #D6E7FF so Primary-on-PrimarySoft reaches 4.5:1.
    primarySoft = Color(0xFFD8E9FF),
    onSurface = Color(0xFF1B1B1F),
    onSurfaceVar = Color(0xFF46464F),
    muted = Color(0xFF67676F),
    divider = Color(0x14000000),
    good = Color(0xFF17A34A),
    goodSoft = Color(0xFFDDF5E4),
    goodText = Color(0xFF0F6B32),
    mid = Color(0xFF8A5A00),
    midSoft = Color(0xFFFCEBC4),
    bad = Color(0xFFC0272D),
    badSoft = Color(0xFFFBE3E3),
    warnSoft = Color(0xFFFFE7A0),
    warnText = Color(0xFF6B4A00),
    onPrimary = Color(0xFFFFFFFF),
    onGood = Color(0xFFFFFFFF),
    onBad = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF1B1B1F),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF8CC2FF),
    glass = Color(0x8CFFFFFF),
    glassStrong = Color(0xE6FFFFFF),
    glassBase = Color(0xFFF7F7FA),
    field = Color(0xCCFFFFFF),
    scrim = Color(0x4D000000),
    scrimSubtle = Color(0x1A000000),
    navSurface = Color(0x66FAFAFA),
    navAccent = Color(0xFF1A84FF),
    navSelection = Color(0x1A000000),
    navPress = Color(0x08000000),
    navShadow = Color(0x1A000000),
    toggleTrack = Color(0x33787878),
    toggleThumb = Color(0xFFFFFFFF),
    toggleShadow = Color(0x0D000000),
    highlight = Color(0xFFFFFFFF),
    shimmer = Color(0xFFFFFFFF),
    iconCutout = Color(0xFFFAF8FA),
)

val DarkPalette = Palette(
    name = "深色",
    bg = Color(0xFF0B0D10),
    card = Color(0xFF171A1F),
    tonal = Color(0xFF20242B),
    tonalStrong = Color(0xFF2A3039),
    primary = Color(0xFF7CB7FF),
    primarySoft = Color(0xFF16324D),
    onSurface = Color(0xFFF2F4F8),
    onSurfaceVar = Color(0xFFC3C8D2),
    muted = Color(0xFFA7AFBD),
    divider = Color(0x33FFFFFF),
    good = Color(0xFF4ADE80),
    goodSoft = Color(0xFF14351F),
    goodText = Color(0xFF7EE2A0),
    mid = Color(0xFFFFC46B),
    midSoft = Color(0xFF3A2A10),
    bad = Color(0xFFFF8A8A),
    badSoft = Color(0xFF3B1D1D),
    warnSoft = Color(0xFF4A350F),
    warnText = Color(0xFFFFD48A),
    onPrimary = Color(0xFF06121F),
    onGood = Color(0xFF052012),
    onBad = Color(0xFF2A0606),
    inverseSurface = Color(0xFFF2F4F8),
    inverseOnSurface = Color(0xFF11151A),
    inversePrimary = Color(0xFF0965CC),
    glass = Color(0xB3141A22),
    glassStrong = Color(0xE61A2028),
    glassBase = Color(0xFF151A21),
    field = Color(0xFF242A33),
    scrim = Color(0x8C000000),
    scrimSubtle = Color(0x40000000),
    navSurface = Color(0x8C121820),
    navAccent = Color(0xFF7CB7FF),
    navSelection = Color(0x1AFFFFFF),
    navPress = Color(0x0AFFFFFF),
    navShadow = Color(0x66000000),
    toggleTrack = Color(0x479AA3B2),
    toggleThumb = Color(0xFFF2F5F8),
    toggleShadow = Color(0x59000000),
    highlight = Color(0xFFEAF2FF),
    shimmer = Color(0xFFFFFFFF),
    iconCutout = Color(0xFF0B0D10),
)

/** Spatial springs (position / size / scale). */
fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

/** Effect springs (color / alpha). */
fun <T> effect(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

val AppFont: FontFamily = FontFamily.SansSerif

@Composable
fun T(
    text: String,
    size: TextUnit = 15.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = C.OnSurface,
    modifier: Modifier = Modifier,
    tabular: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = AppFont,
            fontFeatureSettings = if (tabular) "tnum" else null,
            lineHeight = lineHeight,
            letterSpacing = letterSpacing,
            textAlign = textAlign ?: androidx.compose.ui.text.style.TextAlign.Unspecified,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Gap(modifier: Modifier) = Box(modifier)
