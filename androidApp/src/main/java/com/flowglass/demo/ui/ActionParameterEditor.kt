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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun ActionParameterEditor(section: CommandSection, item: CommandItem,
    onParameter: (String) -> Unit, onDone: () -> Unit, commandTitle: String = "") {
    val meta = item.typeId?.let { id -> com.flowglass.engine.BuiltInComponents.registry().find(id) }
    val parameter = meta?.params?.firstOrNull()
    T(item.name, 16.sp, FontWeight.SemiBold)

    when {
        section == CommandSection.ACTIONS && item.typeId == "action.post_notification" -> {
            NotificationParameterEditor(item, commandTitle, onParameter, onDone)
            return
        }
        section == CommandSection.ACTIONS && parameter?.type == com.flowglass.engine.ParamType.TRI_STATE -> {
            val choices = parameter.options.map { it.label }
            Box(Modifier.fillMaxWidth().clip(RoundedRectangle(22.dp))) {
                SegmentedSelector(choices.map { item.ic to it }, choices.indexOf(item.parameter)) { onParameter(choices[it]) }
            }
        }
        parameter?.type == com.flowglass.engine.ParamType.PERCENTAGE -> {
            val percent = item.parameter.trim().removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 0..100 }
            T(percent?.let { "$it%" } ?: item.parameter.ifBlank { "未设置百分比" }, 20.sp, FontWeight.SemiBold)
            PercentSlider((percent ?: 0).toFloat()) { onParameter("${it.roundToInt().coerceIn(0, 100)}%") }
        }
        parameter?.type == com.flowglass.engine.ParamType.APPLICATION -> {
            ApplicationParameterPicker(item, onParameter)
        }
        parameter?.type == com.flowglass.engine.ParamType.ENUM -> {
            val choices = parameter.options.map { it.label }
            if (choices.isNotEmpty()) {
                SegmentedSelector(choices.map { item.ic to it }, choices.indexOf(item.parameter).coerceAtLeast(0)) {
                    onParameter(choices[it])
                }
            } else {
                ParameterTextField(item.parameter, "item-parameter", onParameter)
            }
        }
        else -> {
            val multiline = parameter?.type == com.flowglass.engine.ParamType.MULTILINE_TEXT
            val integer = parameter?.type == com.flowglass.engine.ParamType.INTEGER
            BasicTextField(item.parameter, onParameter,
                Modifier.fillMaxWidth().heightIn(min = if (multiline) 120.dp else 64.dp).testTag("item-parameter")
                    .clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
                textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(C.Primary),
                keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Text))
            val hint = parameter?.hint?.takeIf { it.isNotBlank() }
            if (hint != null) {
                T(hint, 13.sp, color = C.OnSurfaceVar, lineHeight = 20.sp, modifier = Modifier.testTag("parameter-help"))
            } else {
                val default = parameter?.let { com.flowglass.engine.ParameterCodec.format(it, it.defaultValue) }.orEmpty()
                if (default.isNotEmpty()) T("示例：$default", 13.sp, color = C.OnSurfaceVar,
                    lineHeight = 20.sp, modifier = Modifier.testTag("parameter-help"))
            }
        }
    }
    EditorPill("完成", "完成", action = onDone)
}

@Composable
internal fun ParameterTextField(value: String, tag: String, onValue: (String) -> Unit) {
    BasicTextField(value, onValue,
        Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag)
            .clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
        textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 24.sp),
        cursorBrush = SolidColor(C.Primary))
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
