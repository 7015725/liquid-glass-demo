package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun ActionParameterEditor(section: CommandSection, item: CommandItem,
    onParameter: (String) -> Unit, onDone: () -> Unit) {
    T(item.name, 16.sp, FontWeight.SemiBold)
    if (section == CommandSection.ACTIONS && item.name in setOf("开关 WiFi", "开关蓝牙", "开关勿扰")) {
        val choices = listOf("开启", "关闭", "切换")
        Box(Modifier.fillMaxWidth().clip(RoundedRectangle(22.dp))) {
            SegmentedSelector(choices.map { item.ic to it }, choices.indexOf(item.parameter)) {
                onParameter(choices[it])
            }
        }
    } else if (section == CommandSection.ACTIONS && item.name in setOf("调节音量", "调节亮度")) {
        val percent = item.parameter.trim().removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 0..100 }
        T(percent?.let { "$it%" } ?: item.parameter.ifBlank { "未设置百分比" }, 20.sp, FontWeight.SemiBold)
        PercentSlider((percent ?: 0).toFloat()) { onParameter("${it.roundToInt().coerceIn(0, 100)}%") }
    } else {
        BasicTextField(item.parameter, onParameter,
            Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("item-parameter")
                .clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
            textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 24.sp),
            cursorBrush = SolidColor(C.Primary))
    }
    EditorPill("完成", "完成", action = onDone)
}

@Composable
private fun PercentSlider(value: Float, onValueChange: (Float) -> Unit) {
    val currentChange by rememberUpdatedState(onValueChange)
    val radius = with(LocalDensity.current) { 10.dp.toPx() }
    Canvas(Modifier.fillMaxWidth().height(56.dp).testTag("parameter-percent-slider")
        .semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..100f, 99)
            setProgress { currentChange(it.coerceIn(0f, 100f)); true }
        }
        .pointerInput(radius) {
            fun changeAt(x: Float) {
                currentChange(((x - radius) / (size.width - 2 * radius).coerceAtLeast(1f) * 100).coerceIn(0f, 100f))
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                changeAt(down.position.x)
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) changeAt(change.position.x)
                    change.consume()
                } while (event.changes.any { it.id == down.id && it.pressed })
            }
        }) {
        val left = Offset(radius, center.y)
        val right = Offset((size.width - radius).coerceAtLeast(radius), center.y)
        val thumb = Offset(left.x + (right.x - left.x) * value.coerceIn(0f, 100f) / 100, center.y)
        drawLine(C.Tonal, left, right, 8.dp.toPx(), StrokeCap.Round)
        drawLine(C.Primary, left, thumb, 8.dp.toPx(), StrokeCap.Round)
        drawCircle(C.Primary, radius, thumb)
    }
}
