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
    /** White text on this fill reaches 5.6:1; the old #1A84FF only reached 3.6:1 at 12sp. */
    val Primary = Color(0xFF0965CC)
    val PrimarySoft = Color(0xFFD6E7FF)
    val OnSurface = Color(0xFF1B1B1F)
    val OnSurfaceVar = Color(0xFF46464F)
    /** Secondary text: 4.9:1 on Bg / 5.3:1 on Card. The old #8A8A93 was 3.0:1. */
    val Muted = Color(0xFF67676F)
    val Divider = Color(0x14000000)
    val Good = Color(0xFF17A34A)
    val GoodSoft = Color(0xFFDDF5E4)
    val GoodText = Color(0xFF0F6B32)
    /** Warning text: 5.6:1 on Card. Amber is kept for tinted fills via [MidSoft]. */
    val Mid = Color(0xFF8A5A00)
    val MidSoft = Color(0xFFFCEBC4)
    val Bad = Color(0xFFC0272D)
    val BadSoft = Color(0xFFFBE3E3)
    val WarnSoft = Color(0xFFFFE7A0)
    val WarnText = Color(0xFF6B4A00)
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
