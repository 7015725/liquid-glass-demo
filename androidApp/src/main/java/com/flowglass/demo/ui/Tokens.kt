package com.flowglass.demo.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFFEFEEF2)
    val Card = Color(0xFFFAF8FA)
    val Tonal = Color(0xFFEAE8EE)
    val TonalStrong = Color(0xFFDEDCE3)
    val Primary = Color(0xFF0F62E0)
    val PrimarySoft = Color(0xFFD6E7FF)
    val OnSurface = Color(0xFF1B1B1F)
    val OnSurfaceVar = Color(0xFF46464F)
    val Muted = Color(0xFF6A6A76)
    val Divider = Color(0x14000000)
    val Good = Color(0xFF17A34A)
    val GoodSoft = Color(0xFFDDF5E4)
    val GoodText = Color(0xFF0F6B32)
    val Mid = Color(0xFFE59A12)
    val Bad = Color(0xFFE5484D) // Graphic accents; use BadText for readable text.
    val BadText = Color(0xFFA1231B)
    val Warn = Color(0xFF8A4500)
    val WarnSoft = Color(0xFFFFF1DF)
}

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
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Gap(modifier: Modifier) = Box(modifier)
