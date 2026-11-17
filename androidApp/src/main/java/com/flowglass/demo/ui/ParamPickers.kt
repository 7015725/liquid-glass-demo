package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.engine.ParamSpec
import com.flowglass.engine.VariableScope
import com.kyant.shapes.RoundedRectangle
import kotlin.math.roundToInt

private val durationUnits = listOf("毫秒", "秒", "分钟")
private val durationPresets = listOf("1 秒", "5 秒", "30 秒", "1 分钟")

@Composable
internal fun VariableTextField(
    value: String,
    tag: String,
    variables: List<VariableOption>,
    multiline: Boolean,
    onValue: (String) -> Unit,
) {
    var field by remember(tag) { mutableStateOf(TextFieldValue(value)) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (field.text != value) field = field.copy(text = value, selection = TextRange(value.length))
    }
    BasicTextField(
        field,
        { next -> field = next; onValue(next.text) },
        Modifier.fillMaxWidth().heightIn(min = if (multiline) 120.dp else 64.dp).testTag(tag).imeAutoScroll()
            .clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
        textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 24.sp),
        cursorBrush = SolidColor(C.Primary),
    )
    if (variables.isNotEmpty()) {
        EditorPill("插入变量", "插入变量", modifier = Modifier.padding(top = 6.dp)) { menu = true }
    }
    if (menu) {
        VariableListDialog(variables, onDismiss = { menu = false }) { option ->
            val snippet = "{" + option.name + "}"
            val start = field.selection.min.coerceIn(0, field.text.length)
            val end = field.selection.max.coerceIn(start, field.text.length)
            val next = field.text.substring(0, start) + snippet + field.text.substring(end)
            field = TextFieldValue(next, TextRange(start + snippet.length))
            onValue(next)
            menu = false
        }
    }
}

@Composable
private fun VariableListDialog(variables: List<VariableOption>, onDismiss: () -> Unit, onPick: (VariableOption) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T("插入变量", 16.sp, FontWeight.SemiBold)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VariableScope.values().forEach { scope ->
                        val rows = variables.filter { it.scope == scope }
                        if (rows.isNotEmpty()) {
                            item(key = "group-$scope") {
                                T(VariableCatalog.scopeLabel(scope), 12.sp, FontWeight.SemiBold, color = C.Muted)
                            }
                            items(rows, key = { "${it.scope}-${it.ownerId}-${it.name}" }) { option ->
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedRectangle(14.dp)).background(C.Tonal)
                                        .pressable { onPick(option) }.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    T("{${option.name}}", 13.sp, FontWeight.SemiBold)
                                    val detail = buildString {
                                        append(VariableCatalog.typeLabel(option.type))
                                        if (option.ownerTitle.isNotBlank()) append(" · ${option.ownerTitle}")
                                    }
                                    T(detail, 11.sp, color = C.Muted)
                                    if (option.note.isNotBlank()) T(option.note, 11.sp, color = C.OnSurfaceVar)
                                }
                            }
                        }
                    }
                }
                EditorPill("取消", "取消插入变量", action = onDismiss)
            }
        }
    }
}

@Composable
internal fun TimeField(value: String, tag: String, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card)
            .testTag(tag)
            .pressable { open = true }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(value.ifBlank { "选择时间" }, 16.sp, color = if (value.isBlank()) C.Muted else C.OnSurface,
            modifier = Modifier.weight(1f))
        T("选择", 13.sp, color = C.Primary)
    }
    if (open) {
        TimePickerDialog(value, onDismiss = { open = false }) {
            onValue(it)
            open = false
        }
    }
}

@Composable
internal fun TimeRangeField(value: String, onValue: (String) -> Unit) {
    val parts = value.split(Regex("\\s*[–—~-]\\s*"))
    val from = parts.getOrNull(0)?.takeIf { it.matches(Regex("([01]?\\d|2[0-3]):[0-5]\\d")) } ?: "08:00"
    val to = parts.getOrNull(1)?.takeIf { it.matches(Regex("([01]?\\d|2[0-3]):[0-5]\\d")) } ?: "22:00"
    T("开始", 13.sp, color = C.Muted)
    TimeField(from, "param-range-from") { onValue("$it–$to") }
    T("结束", 13.sp, color = C.Muted)
    TimeField(to, "param-range-to") { onValue("$from–$it") }
    T("支持跨夜；结束时间早于开始时间时按次日处理。", 12.sp, color = C.OnSurfaceVar)
}

@Composable
private fun TimePickerDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val match = Regex("([01]?\\d|2[0-3]):([0-5]\\d)").matchEntire(initial.trim())
    var hour by remember(initial) { mutableIntStateOf(match?.groupValues?.get(1)?.toIntOrNull() ?: 8) }
    var minute by remember(initial) { mutableIntStateOf(match?.groupValues?.get(2)?.toIntOrNull() ?: 0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                T("选择时间", 17.sp, FontWeight.SemiBold)
                T("24 小时制", 12.sp, color = C.Muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberWheel(0..23, hour, "小时", Modifier.weight(1f)) { hour = it }
                    NumberWheel(0..59, minute, "分钟", Modifier.weight(1f)) { minute = it }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorPill("取消", "取消时间选择", action = onDismiss, modifier = Modifier.weight(1f))
                    EditorPill("确定", "确定时间", action = { onConfirm("%02d:%02d".format(hour, minute)) },
                        modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NumberWheel(values: IntRange, selected: Int, label: String, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) {
        val index = values.indexOf(selected)
        if (index >= 0) state.scrollToItem(index)
    }
    Column(modifier) {
        T(label, 12.sp, color = C.Muted)
        LazyColumn(Modifier.fillMaxWidth().height(180.dp).testTag("time-wheel-$label"), state = state) {
            items(values.toList()) { value ->
                val on = value == selected
                Box(
                    Modifier.fillMaxWidth().clip(RoundedRectangle(12.dp))
                        .background(if (on) C.Primary else C.Tonal)
                        .pressable { onSelect(value) }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { T("%02d".format(value), 18.sp, FontWeight.SemiBold, if (on) C.OnPrimary else C.OnSurface) }
            }
        }
    }
}

internal fun parseDuration(value: String): Pair<String, String>? {
    val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(毫秒|秒|分钟)").matchEntire(value.trim()) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DurationField(value: String, tag: String, onValue: (String) -> Unit) {
    val parsed = parseDuration(value)
    var number by remember(value) { mutableStateOf(parsed?.first ?: "") }
    var unit by remember(value) { mutableStateOf(parsed?.second ?: "秒") }

    fun emit(nextNumber: String = number, nextUnit: String = unit) {
        onValue("${nextNumber.ifBlank { "0" }} $nextUnit")
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            number,
            { next -> number = next; emit(next, unit) },
            Modifier.weight(1f).heightIn(min = 56.dp).testTag(tag).imeAutoScroll()
                .clip(RoundedRectangle(20.dp)).background(C.Card).padding(12.dp),
            textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp),
            cursorBrush = SolidColor(C.Primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ),
        )
        FilterChips(durationUnits, durationUnits.indexOf(unit).coerceAtLeast(0), onSelect = { index ->
            unit = durationUnits[index]; emit(number, unit)
        }, modifier = Modifier.width(160.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        durationPresets.forEach { preset ->
            EditorPill(preset, "设置时长$preset") {
                val p = parseDuration(preset) ?: return@EditorPill
                number = p.first; unit = p.second; emit(p.first, p.second)
            }
        }
    }
}

@Composable
internal fun StepperField(spec: ParamSpec, value: String, tag: String, onValue: (String) -> Unit) {
    val current = value.toLongOrNull()
    val min = spec.minValue ?: 0L
    val max = spec.maxValue ?: Long.MAX_VALUE
    fun commit(next: Long) = onValue(next.coerceIn(min, max).toString())
    Row(
        Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EditorPill("－", "减少${spec.label}") { commit((current ?: min) - 1) }
        BasicTextField(
            value,
            { text -> text.toLongOrNull()?.let(::commit) },
            Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag).imeAutoScroll()
                .clip(RoundedRectangle(16.dp)).background(C.Tonal).padding(10.dp),
            textStyle = TextStyle(color = C.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(C.Primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ),
        )
        EditorPill("＋", "增加${spec.label}") { commit((current ?: min) + 1) }
    }
}

@Composable
internal fun IntegerRangeField(spec: ParamSpec, value: String, tag: String, onValue: (String) -> Unit) {
    val suffix = spec.suffix
    val parts = value.split(Regex("\\s*[–—~]\\s*|\\s+-\\s+"))
    val lower = parts.getOrNull(0)?.removeSuffix(suffix)?.trim()?.toIntOrNull() ?: (spec.minValue ?: 0L).toInt()
    val upper = parts.getOrNull(1)?.removeSuffix(suffix)?.trim()?.toIntOrNull() ?: (spec.maxValue ?: 100L).toInt()
    val min = (spec.minValue ?: 0L).toInt()
    val max = (spec.maxValue ?: 100L).toInt()
    fun emit(a: Int, b: Int) {
        val lo = a.coerceIn(min, max)
        val hi = b.coerceIn(min, max)
        onValue("$lo$suffix–${maxOf(lo, hi)}$suffix")
    }
    // Keep the raw field so existing text-based callers and accessibility can edit both endpoints.
    ParameterTextField(value, "item-parameter", onValue)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            T("$lower$suffix", 13.sp, color = C.OnSurfaceVar)
            PercentSlider(lower.toFloat(), "parameter-range-slider") { emit(it.roundToInt(), upper) }
        }
        Box(Modifier.weight(1f)) {
            T("$upper$suffix", 13.sp, color = C.OnSurfaceVar)
            PercentSlider(upper.toFloat(), "parameter-range-slider") { emit(lower, it.roundToInt()) }
        }
    }
}
