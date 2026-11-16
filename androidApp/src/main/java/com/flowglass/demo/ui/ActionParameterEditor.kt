package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.system.trigger.canScheduleExactAlarms
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ParamSpec
import com.flowglass.engine.ParamType
import com.flowglass.engine.ParameterCodec
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

private val weekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
internal fun ActionParameterEditor(
    section: CommandSection,
    item: CommandItem,
    onParameter: (String) -> Unit,
    onDone: () -> Unit,
    commandTitle: String = "",
    onAuthorization: (Int) -> Unit = {},
    targets: List<String> = emptyList(),
    showDone: Boolean = true,
) {
    val meta = item.typeId?.let { id -> com.flowglass.engine.BuiltInComponents.registry().find(id) }
    val parameter = meta?.params?.firstOrNull()

    if (meta != null && (item.typeId == "trigger.location_enter" || item.typeId == "trigger.location_exit")) {
        LocationParameterEditor(meta, item, onParameter, onDone, showDone)
        return
    }

    if (item.typeId == "action.run_rule") {
        RunRulePicker(item, targets, onParameter, onDone, showDone)
        return
    }

    if (meta != null && meta.params.size > 1 && item.typeId != "action.post_notification") {
        MultiParameterEditor(meta, item, onParameter, onAuthorization)
        if (showDone) EditorPill("完成", "完成", action = onDone)
        return
    }

    when {
        section == CommandSection.ACTIONS && item.typeId == "action.post_notification" -> {
            NotificationParameterEditor(item, commandTitle, onParameter, onDone, showDone)
            return
        }
        section == CommandSection.ACTIONS && parameter?.type == ParamType.TRI_STATE -> {
            val choices = parameter.options.map { it.label }
            Box(Modifier.fillMaxWidth().clip(RoundedRectangle(22.dp))) {
                SegmentedSelector(choices.map { item.ic to it }, choices.indexOf(item.parameter)) { onParameter(choices[it]) }
            }
        }
        parameter?.type == ParamType.WEEKDAYS -> {
            WeekdayPicker(parameter, item.parameter, onParameter)
        }
        parameter?.type == ParamType.PERCENTAGE -> {
            val percent = item.parameter.trim().removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 0..100 }
            T(percent?.let { "$it%" } ?: item.parameter.ifBlank { "未设置百分比" }, 20.sp, FontWeight.SemiBold)
            PercentSlider((percent ?: 0).toFloat()) { onParameter("${it.roundToInt().coerceIn(0, 100)}%") }
        }
        parameter?.type == ParamType.APPLICATION -> {
            ApplicationParameterPicker(item, onParameter)
        }
        parameter?.type == ParamType.ENUM -> {
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
            val multiline = parameter?.type == ParamType.MULTILINE_TEXT
            val integer = parameter?.type == ParamType.INTEGER
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
    if (showDone) EditorPill("完成", "完成", action = onDone)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiParameterEditor(
    meta: ComponentMeta,
    item: CommandItem,
    onParameter: (String) -> Unit,
    onAuthorization: (Int) -> Unit,
) {
    var fields by remember(item.id) { mutableStateOf(ParameterForm.fields(meta, item.parameter)) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }

    fun update(key: String, value: String) {
        val next = fields + (key to value)
        fields = next
        ParameterForm.compose(meta, next)
            .onSuccess { onParameter(it); error = null }
            .onFailure { error = it.message?.replace('\n', ' ')?.take(120) }
    }

    if (item.typeId == "trigger.time" && !canScheduleExactAlarms(androidx.compose.ui.platform.LocalContext.current)) {
        FlowRow(
            Modifier.fillMaxWidth().clip(RoundedRectangle(20.dp)).background(C.Tonal).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T("未授予「精确闹钟」权限，触发时间可能有几分钟误差。", 12.sp, color = C.OnSurfaceVar,
                lineHeight = 18.sp)
            Box(
                Modifier.clip(Capsule()).background(C.Primary).pressable { onAuthorization(SettingsPage.Permissions) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { T("去授权", 12.sp, FontWeight.SemiBold, C.OnPrimary, maxLines = 1) }
        }
    }
    error?.let { T("参数无效：$it", 12.sp, color = C.Bad) }

    meta.params.forEach { spec ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(ParameterForm.fieldLabel(spec), 14.sp, FontWeight.SemiBold)
            when (spec.type) {
                ParamType.TIME -> ParameterTextField(fields[spec.key].orEmpty(), "param-${spec.key}") {
                    update(spec.key, it)
                }
                ParamType.WEEKDAYS -> WeekdayPicker(spec, fields[spec.key].orEmpty()) { update(spec.key, it) }
                ParamType.PERCENTAGE -> {
                    val value = fields[spec.key].orEmpty()
                    val number = value.removeSuffix("%").trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
                    T(value.ifBlank { "0%" }, 20.sp, FontWeight.Bold, tabular = true)
                    PercentSlider(number.toFloat()) { update(spec.key, "${it.toInt().coerceIn(0, 100)}%") }
                }
                ParamType.ENUM -> {
                    val labels = spec.options.map { it.label }
                    if (labels.isEmpty()) {
                        ParameterTextField(fields[spec.key].orEmpty(), "param-${spec.key}") { update(spec.key, it) }
                    } else {
                        SegmentedSelector(spec.options.map { item.ic to it.label },
                            labels.indexOf(fields[spec.key].orEmpty()).coerceAtLeast(0)) {
                            update(spec.key, labels[it])
                        }
                    }
                }
                else -> ParameterTextField(fields[spec.key].orEmpty(), "param-${spec.key}") { update(spec.key, it) }
            }
            if (spec.hint.isNotBlank()) T(spec.hint, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayPicker(spec: ParamSpec, value: String, onValue: (String) -> Unit) {
    val selected = runCatching { ParameterCodec.weekdays(value) }
        .getOrElse { "1,2,3,4,5,6,7" }
        .split(',').mapNotNull { it.toIntOrNull() }.toSet()
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
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                T(label.takeLast(1), 13.sp, FontWeight.SemiBold,
                    if (on) C.OnPrimary else C.OnSurfaceVar, maxLines = 1)
            }
        }
    }
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
internal fun PercentSlider(value: Float, onValueChange: (Float) -> Unit) {
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


private const val WAIT_LABEL = "等它执行完再继续"
private const val NO_WAIT_LABEL = "发起后立即继续"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunRulePicker(
    item: CommandItem,
    targets: List<String>,
    onParameter: (String) -> Unit,
    onDone: () -> Unit,
    showDone: Boolean,
) {
    val pieces = item.parameter.split('|')
    val selected = pieces.getOrNull(0)?.trim().orEmpty()
    val wait = pieces.getOrNull(1)?.contains("等它执行完") ?: true
    var query by remember { mutableStateOf("") }
    T("目标指令", 13.sp, color = C.Muted)
    BasicTextField(query, { query = it },
        Modifier.fillMaxWidth().clip(RoundedRectangle(20.dp)).background(C.Card).padding(12.dp),
        textStyle = TextStyle(color = C.OnSurface, fontSize = 14.sp), singleLine = true,
        cursorBrush = SolidColor(C.Primary),
        decorationBox = { inner -> Box { if (query.isEmpty()) T("搜索指令名称", 14.sp, color = C.Muted); inner() } })
    val filtered = targets.filter { it.contains(query.trim(), ignoreCase = true) }
    if (selected.isNotEmpty() && targets.none { it == selected }) {
        T("当前目标「$selected」已不存在，重新选一个", 12.sp, color = C.Bad)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        filtered.take(12).forEach { name ->
            val isSelected = name == selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp))
                    .background(if (isSelected) C.PrimarySoft else C.Card)
                    .editorAction("选择目标：$name") {
                        onParameter("$name|${if (wait) WAIT_LABEL else NO_WAIT_LABEL}")
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(name, 14.sp, color = if (isSelected) C.Primary else C.OnSurface, modifier = Modifier.weight(1f))
                if (isSelected) T("已选", 11.sp, color = C.Primary)
            }
        }
        if (filtered.isEmpty()) T("没有匹配的指令", 12.sp, color = C.Muted)
    }
    T("执行方式", 13.sp, color = C.Muted, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(WAIT_LABEL to true, NO_WAIT_LABEL to false).forEach { (label, isWait) ->
            EditorPill(
                if (wait == isWait) "● $label" else label,
                "设置执行方式：$label",
            ) { if (selected.isNotEmpty()) onParameter("$selected|$label") }
        }
    }
    T(
        if (wait) "会等它跑完再继续，共用步数预算，父任务停止时一起取消。"
        else "立即发起一次独立运行，有自己的记录和停止按钮，不随父任务结束。",
        12.sp, color = C.Muted, lineHeight = 17.sp,
    )
    if (showDone) EditorPill("完成", "完成", action = onDone)
}
