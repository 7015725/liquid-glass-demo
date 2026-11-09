package com.flowglass.demo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Hand-drawn line icon set, 24x24 grid, 2dp round strokes. */
enum class Ic {
    Home, Grid, List, Settings, Bolt, Copy, ChevronRight, UpDown, ArrowUp, ArrowDown,
    Play, Stop, Plus, Bell, Refresh, Shield, Wifi, Moon, Battery, Timer, Location,
    Leaf, Gauge, Rocket, Clock, Phone, Bluetooth, Message, Camera, Info, Search, Accessibility,
    Tap, Back, Lock, Terminal, Puzzle, Key
}

@Composable
fun LineIcon(ic: Ic, color: Color, size: Dp = 24.dp, filled: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            drawIcon(ic, color, filled, 2f / (s / density) * (1f))
        }
    }
}

private fun DrawScope.drawIcon(ic: Ic, color: Color, filled: Boolean, strokeUnits: Float) {
    // strokeUnits keeps a visual 2dp line regardless of icon size (in 24-grid units)
    val sw = strokeUnits.coerceIn(1.2f, 2.6f)
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val style = if (filled) Fill else st
    fun p(block: Path.() -> Unit) = Path().apply(block)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)

    when (ic) {
        Ic.Home -> {
            val path = p {
                moveTo(4f, 10.5f); lineTo(12f, 4f); lineTo(20f, 10.5f); lineTo(20f, 19f)
                quadraticTo(20f, 20f, 19f, 20f); lineTo(5f, 20f); quadraticTo(4f, 20f, 4f, 19f); close()
            }
            drawPath(path, color, style = style)
            if (filled) drawRoundRect(Color.White, Offset(10f, 14f), Size(4f, 6f), CornerRadius(1f))
            else line(10f, 20f, 10f, 15f).also { line(14f, 20f, 14f, 15f); line(10f, 15f, 14f, 15f) }
        }
        Ic.Grid -> listOf(Offset(4f, 4f), Offset(13f, 4f), Offset(4f, 13f), Offset(13f, 13f)).forEach {
            drawRoundRect(color, it, Size(7f, 7f), CornerRadius(2.2f), style = style)
        }
        Ic.List -> {
            if (filled) drawRoundRect(color, Offset(3f, 3f), Size(18f, 18f), CornerRadius(5f))
            else drawRoundRect(color, Offset(3f, 3f), Size(18f, 18f), CornerRadius(5f), style = st)
            val c = if (filled) Color.White else color
            drawLine(c, Offset(8f, 9f), Offset(16f, 9f), sw, StrokeCap.Round)
            drawLine(c, Offset(8f, 12f), Offset(16f, 12f), sw, StrokeCap.Round)
            drawLine(c, Offset(8f, 15f), Offset(13f, 15f), sw, StrokeCap.Round)
        }
        Ic.Settings -> {
            val path = Path()
            val n = 8
            for (i in 0 until n * 2) {
                val a = (i / (n * 2f)) * 2 * PI - PI / 2
                val r = if (i % 2 == 0) 9f else 7f
                val x = 12f + (r * cos(a)).toFloat(); val y = 12f + (r * sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color, style = style)
            drawCircle(if (filled) Color.White else color, 3f, Offset(12f, 12f), style = if (filled) Fill else st)
        }
        Ic.Bolt -> drawPath(p {
            moveTo(13.5f, 3f); lineTo(5.5f, 13.5f); lineTo(11.5f, 13.5f); lineTo(10.5f, 21f)
            lineTo(18.5f, 10.5f); lineTo(12.5f, 10.5f); close()
        }, color, style = style)
        Ic.Copy -> {
            drawRoundRect(color, Offset(8f, 8f), Size(12f, 12f), CornerRadius(3f), style = st)
            drawPath(p { moveTo(16f, 5.5f); quadraticTo(16f, 4f, 14.5f, 4f); lineTo(5.5f, 4f); quadraticTo(4f, 4f, 4f, 5.5f); lineTo(4f, 14.5f); quadraticTo(4f, 16f, 5.5f, 16f) }, color, style = st)
        }
        Ic.ChevronRight -> drawPath(p { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) }, color, style = st)
        Ic.UpDown -> {
            drawPath(p { moveTo(8f, 9.5f); lineTo(12f, 5.5f); lineTo(16f, 9.5f) }, color, style = st)
            drawPath(p { moveTo(8f, 14.5f); lineTo(12f, 18.5f); lineTo(16f, 14.5f) }, color, style = st)
        }
        Ic.ArrowUp -> { line(12f, 19f, 12f, 5f); drawPath(p { moveTo(6.5f, 10.5f); lineTo(12f, 5f); lineTo(17.5f, 10.5f) }, color, style = st) }
        Ic.ArrowDown -> { line(12f, 5f, 12f, 19f); drawPath(p { moveTo(6.5f, 13.5f); lineTo(12f, 19f); lineTo(17.5f, 13.5f) }, color, style = st) }
        Ic.Play -> drawPath(p {
            moveTo(8f, 5.5f); quadraticTo(8f, 4.2f, 9.2f, 4.9f); lineTo(19f, 11f)
            quadraticTo(20f, 12f, 19f, 13f); lineTo(9.2f, 19.1f); quadraticTo(8f, 19.8f, 8f, 18.5f); close()
        }, color, style = Fill)
        Ic.Stop -> drawRoundRect(color, Offset(6.5f, 6.5f), Size(11f, 11f), CornerRadius(2.5f))
        Ic.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        Ic.Bell -> {
            drawPath(p {
                moveTo(6f, 16.5f); lineTo(6f, 11f); cubicTo(6f, 7.5f, 8.5f, 5f, 12f, 5f)
                cubicTo(15.5f, 5f, 18f, 7.5f, 18f, 11f); lineTo(18f, 16.5f); lineTo(19.5f, 18f); lineTo(4.5f, 18f); close()
            }, color, style = style)
            line(10.5f, 20.5f, 13.5f, 20.5f)
        }
        Ic.Refresh -> {
            drawArc(color, -60f, 290f, false, Offset(5f, 5f), Size(14f, 14f), style = st)
            drawPath(p { moveTo(15.5f, 3.5f); lineTo(18.6f, 6f); lineTo(15.4f, 8.6f) }, color, style = st)
        }
        Ic.Shield -> drawPath(p {
            moveTo(12f, 3.5f); lineTo(19f, 6.5f); lineTo(19f, 11.5f); cubicTo(19f, 16f, 16f, 19.2f, 12f, 20.5f)
            cubicTo(8f, 19.2f, 5f, 16f, 5f, 11.5f); lineTo(5f, 6.5f); close()
        }, color, style = style)
        Ic.Wifi -> {
            drawArc(color, 225f, 90f, false, Offset(2.5f, 5f), Size(19f, 19f), style = st)
            drawArc(color, 225f, 90f, false, Offset(6f, 8.5f), Size(12f, 12f), style = st)
            drawCircle(color, 1.6f, Offset(12f, 17.5f))
        }
        Ic.Moon -> drawPath(p {
            moveTo(19.5f, 14.5f); cubicTo(16f, 16f, 11f, 14.5f, 9.5f, 10.5f); cubicTo(8.5f, 8f, 9f, 5.5f, 10.5f, 4f)
            cubicTo(6.5f, 4.5f, 4f, 8f, 4f, 12f); cubicTo(4f, 16.5f, 7.5f, 20f, 12f, 20f); cubicTo(15.5f, 20f, 18.5f, 17.8f, 19.5f, 14.5f); close()
        }, color, style = style)
        Ic.Battery -> {
            drawRoundRect(color, Offset(3f, 7f), Size(16f, 10f), CornerRadius(3f), style = st)
            line(21.5f, 10.5f, 21.5f, 13.5f)
            drawRoundRect(color, Offset(5.5f, 9.5f), Size(8f, 5f), CornerRadius(1.2f))
        }
        Ic.Timer -> {
            drawCircle(color, 7.5f, Offset(12f, 13.5f), style = st)
            line(12f, 13.5f, 12f, 10f); line(10f, 3.5f, 14f, 3.5f); line(18f, 7f, 19.2f, 5.8f)
        }
        Ic.Clock -> {
            drawCircle(color, 8.5f, Offset(12f, 12f), style = st)
            line(12f, 12f, 12f, 7.5f); line(12f, 12f, 15f, 14f)
        }
        Ic.Location -> {
            drawPath(p {
                moveTo(12f, 21f); cubicTo(8f, 16.5f, 5.5f, 13f, 5.5f, 10f); cubicTo(5.5f, 6.2f, 8.4f, 3.5f, 12f, 3.5f)
                cubicTo(15.6f, 3.5f, 18.5f, 6.2f, 18.5f, 10f); cubicTo(18.5f, 13f, 16f, 16.5f, 12f, 21f); close()
            }, color, style = st)
            drawCircle(color, 2.4f, Offset(12f, 10f), style = st)
        }
        Ic.Leaf -> {
            drawPath(p { moveTo(5f, 19f); cubicTo(5f, 10f, 10f, 5f, 19f, 5f); cubicTo(19f, 14f, 14f, 19f, 5f, 19f); close() }, color, style = style)
            line(5f, 19f, 13f, 11f)
        }
        Ic.Gauge -> {
            drawArc(color, 150f, 240f, false, Offset(3.5f, 4.5f), Size(17f, 17f), style = st)
            line(12f, 13f, 15.5f, 9f)
            drawCircle(color, 1.5f, Offset(12f, 13f))
        }
        Ic.Rocket -> {
            drawPath(p {
                moveTo(12f, 3f); cubicTo(15.5f, 5.5f, 16.5f, 9.5f, 15.5f, 15f); lineTo(8.5f, 15f)
                cubicTo(7.5f, 9.5f, 8.5f, 5.5f, 12f, 3f); close()
            }, color, style = style)
            line(9f, 17.5f, 9f, 20f); line(12f, 17.5f, 12f, 21f); line(15f, 17.5f, 15f, 20f)
        }
        Ic.Phone -> {
            drawRoundRect(color, Offset(6.5f, 3f), Size(11f, 18f), CornerRadius(3f), style = st)
            line(10.5f, 18f, 13.5f, 18f)
        }
        Ic.Bluetooth -> drawPath(p {
            moveTo(7f, 7.5f); lineTo(16.5f, 16f); lineTo(12f, 20f); lineTo(12f, 4f); lineTo(16.5f, 8f); lineTo(7f, 16.5f)
        }, color, style = st)
        Ic.Message -> drawPath(p {
            moveTo(5f, 5f); lineTo(19f, 5f); quadraticTo(20f, 5f, 20f, 6f); lineTo(20f, 15f); quadraticTo(20f, 16f, 19f, 16f)
            lineTo(10f, 16f); lineTo(6f, 19.5f); lineTo(6f, 16f); lineTo(5f, 16f); quadraticTo(4f, 16f, 4f, 15f)
            lineTo(4f, 6f); quadraticTo(4f, 5f, 5f, 5f); close()
        }, color, style = style)
        Ic.Camera -> {
            drawRoundRect(color, Offset(3f, 7f), Size(18f, 13f), CornerRadius(3f), style = st)
            drawPath(p { moveTo(8f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(16f, 7f) }, color, style = st)
            drawCircle(color, 3.3f, Offset(12f, 13.5f), style = st)
        }
        Ic.Info -> {
            drawCircle(color, 8.5f, Offset(12f, 12f), style = st)
            line(12f, 11f, 12f, 16f); drawCircle(color, 1.2f, Offset(12f, 8f))
        }
        Ic.Search -> {
            drawCircle(color, 6.5f, Offset(10.5f, 10.5f), style = st)
            line(15.3f, 15.3f, 20f, 20f)
        }
        Ic.Tap -> {
            if (filled) drawCircle(color, 4f, Offset(12f, 12f)) else drawCircle(color, 3.2f, Offset(12f, 12f), style = st)
            drawArc(color, 200f, 140f, false, Offset(5f, 5f), Size(14f, 14f), style = st)
            drawArc(color, 20f, 140f, false, Offset(5f, 5f), Size(14f, 14f), style = st)
            drawArc(color, 210f, 120f, false, Offset(1.5f, 1.5f), Size(21f, 21f), style = st)
        }
        Ic.Back -> drawPath(p { moveTo(14.5f, 6f); lineTo(8.5f, 12f); lineTo(14.5f, 18f) }, color, style = st)
        Ic.Lock -> {
            drawRoundRect(color, Offset(5f, 10.5f), Size(14f, 10f), CornerRadius(3f), style = style)
            drawPath(p { moveTo(8f, 10.5f); lineTo(8f, 8f); cubicTo(8f, 5.5f, 9.8f, 4f, 12f, 4f); cubicTo(14.2f, 4f, 16f, 5.5f, 16f, 8f); lineTo(16f, 10.5f) }, color, style = st)
        }
        Ic.Terminal -> {
            drawRoundRect(color, Offset(3f, 4.5f), Size(18f, 15f), CornerRadius(3.5f), style = st)
            drawPath(p { moveTo(7.5f, 9.5f); lineTo(10.5f, 12f); lineTo(7.5f, 14.5f) }, color, style = st)
            line(12.5f, 15f, 16.5f, 15f)
        }
        Ic.Puzzle -> drawPath(p {
            moveTo(5f, 8f); lineTo(9f, 8f); cubicTo(9f, 5f, 13f, 5f, 13f, 8f); lineTo(17f, 8f); lineTo(17f, 12f)
            cubicTo(20f, 12f, 20f, 16f, 17f, 16f); lineTo(17f, 20f); lineTo(5f, 20f); close()
        }, color, style = style)
        Ic.Key -> {
            drawCircle(color, 4f, Offset(8f, 12f), style = st)
            line(12f, 12f, 20f, 12f); line(17f, 12f, 17f, 15f); line(20f, 12f, 20f, 14.5f)
        }
        Ic.Accessibility -> {
            drawCircle(color, 2f, Offset(12f, 5f))
            line(5f, 9f, 19f, 9f); line(12f, 9f, 12f, 14f)
            line(12f, 14f, 8.5f, 20f); line(12f, 14f, 15.5f, 20f)
        }
    }
}
