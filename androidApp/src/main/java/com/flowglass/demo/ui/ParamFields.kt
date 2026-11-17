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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.engine.ParamEditor
import com.flowglass.engine.ParamSource
import com.flowglass.engine.ParamSpec
import com.flowglass.engine.ParameterCodec
import com.flowglass.engine.resolvedEditor
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.roundToInt

private val weekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * One parameter field. The field only receives a [ParamSpec]; it never branches on a component
 * type id, so a newly registered component gets the right control from its metadata alone.
 */
@Composable
internal fun ParamField(
    spec: ParamSpec,
    current: String,
    tag: String,
    onValue: (String) -> Unit,
    targets: List<String> = emptyList(),
    variables: List<VariableOption> = emptyList(),
    onOpenMethodHooks: () -> Unit = {},
) {
    when (spec.source) {
        ParamSource.PAIRED_BLUETOOTH -> BluetoothNameField(current, tag, onValue)
        ParamSource.WIFI_NETWORKS -> WifiNameField(current, tag, onValue)
        ParamSource.METHOD_HOOKS -> MethodHookField(current, tag, onValue, onOpenMethodHooks)
        else -> when (spec.resolvedEditor()) {
            ParamEditor.SEGMENTED -> SegmentedField(spec, current, onValue)
            ParamEditor.SLIDER -> PercentageField(current, onValue)
            ParamEditor.WEEKDAYS -> WeekdayField(current, onValue)
            ParamEditor.APPLICATION -> ApplicationParameterPicker(
                current = current,
                spec = spec,
                stateKey = tag,
                onParameter = onValue,
            )
            ParamEditor.RULE -> RuleField(targets, current, tag, onValue)
            ParamEditor.LOCATION -> LocationField(current, tag, onValue)
            ParamEditor.MULTILINE -> VariableTextField(current, tag, variables, multiline = true, onValue)
            ParamEditor.TEXT -> VariableTextField(current, tag, variables, multiline = false, onValue)
            ParamEditor.METHOD_HOOK -> MethodHookField(current, tag, onValue, onOpenMethodHooks)
            ParamEditor.STEPPER -> StepperField(spec, current, tag, onValue)
            ParamEditor.TIME -> TimeField(current, tag, onValue)
            ParamEditor.TIME_RANGE -> TimeRangeField(current, onValue)
            ParamEditor.INT_RANGE -> IntegerRangeField(spec, current, tag, onValue)
            ParamEditor.DURATION -> DurationField(current, tag, onValue)
            ParamEditor.AUTO -> ParameterTextField(current, tag, onValue)
        }
    }
}

@Composable
private fun SegmentedField(spec: ParamSpec, current: String, onValue: (String) -> Unit) {
    val labels = spec.options.map { it.label }
    if (labels.isEmpty()) {
        ParameterTextField(current, "item-parameter", onValue)
        return
    }
    val selected = spec.options.indexOfFirst { it.value == current || it.label == current }
        .coerceAtLeast(0)
    FilterChips(labels, selected, onSelect = { index -> onValue(spec.options[index].label) })
}

@Composable
private fun PercentageField(current: String, onValue: (String) -> Unit) {
    val percent = current.trim().removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 0..100 }
    T(percent?.let { "$it%" } ?: current.ifBlank { "未设置百分比" }, 20.sp, FontWeight.SemiBold, tabular = true)
    PercentSlider((percent ?: 0).toFloat()) { onValue("${it.roundToInt().coerceIn(0, 100)}%") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayField(current: String, onValue: (String) -> Unit) {
    val selected = runCatching { ParameterCodec.weekdays(current) }
        .getOrElse { "1,2,3,4,5,6,7" }
        .split(',').mapNotNull { it.toIntOrNull() }.toSet()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("每天" to "1,2,3,4,5,6,7", "工作日" to "1,2,3,4,5", "周末" to "6,7").forEach { (label, days) ->
            val selectedDays = days.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            val on = selected == selectedDays
            Box(
                Modifier.height(34.dp).clip(Capsule())
                    .background(if (on) C.PrimarySoft else C.Card)
                    .pressable(0.94f) { onValue(ParameterCodec.weekdayLabel(selectedDays.sorted().joinToString(","))) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { T(label, 12.sp, color = if (on) C.Primary else C.OnSurfaceVar) }
        }
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        weekdayLabels.forEachIndexed { index, label ->
            val day = index + 1
            val on = day in selected
            Box(
                Modifier.height(44.dp).widthIn(min = 40.dp).clip(Capsule())
                    .background(if (on) C.Primary else C.Tonal)
                    .pressable(0.94f) {
                        val next = if (on) selected - day else selected + day
                        if (next.isNotEmpty()) onValue(ParameterCodec.weekdayLabel(next.sorted().joinToString(",")))
                    },
                contentAlignment = Alignment.Center,
            ) {
                T(label.takeLast(1), 13.sp, FontWeight.SemiBold,
                    if (on) C.OnPrimary else C.OnSurfaceVar, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NumberTextField(value: String, tag: String, onValue: (String) -> Unit) {
    BasicTextField(value, onValue,
        Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag).imeAutoScroll()
            .clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
        textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 24.sp),
        cursorBrush = SolidColor(C.Primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun RuleField(targets: List<String>, current: String, tag: String, onValue: (String) -> Unit) {
    var query by rememberSaveable(tag) { mutableStateOf("") }
    ParameterTextField(query, "$tag-search", { query = it })
    T("点选后保存指令名称；返回不会清空原选择。", 12.sp, color = C.OnSurfaceVar)
    if (current.isNotBlank() && targets.none { it == current }) {
        T("当前目标「$current」已不存在，重新选一个", 12.sp, color = C.Bad)
    }
    val filtered = targets.filter { it.contains(query.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        filtered.take(12).forEach { name ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp))
                    .background(if (name == current) C.PrimarySoft else C.Card)
                    .editorAction("选择目标：$name") { onValue(name) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(name, 14.sp, color = if (name == current) C.Primary else C.OnSurface,
                    modifier = Modifier.weight(1f))
                if (name == current) T("已选", 11.sp, color = C.Primary)
            }
        }
        if (filtered.isEmpty()) T("没有匹配的指令", 12.sp, color = C.Muted)
    }
}

@Composable
private fun LocationField(current: String, tag: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    ParameterTextField(current, tag, onValue)
    EditorPill("填入当前位置", "填入当前位置") {
        currentLocation(context) { location ->
            if (location == null) message = "无法读取当前位置，请检查定位权限和定位开关"
            else {
                val text = "${location.latitude},${location.longitude}"
                message = "已填入当前位置：$text"
                onValue(text)
            }
        }
    }
    message?.let { T(it, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp) }
}
