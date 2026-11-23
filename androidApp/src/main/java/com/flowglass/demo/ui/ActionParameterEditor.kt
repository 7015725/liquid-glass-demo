package com.flowglass.demo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.system.trigger.canScheduleExactAlarms
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ParameterCodec
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.roundToInt

/**
 * Shared parameter page. Fields are selected from ParamSpec metadata; this file deliberately has
 * no `typeId` branches. The special JSON/string codecs live in ParameterCodec, not here.
 */
@Composable
internal fun ActionParameterEditor(
    section: CommandSection,
    item: CommandItem,
    onParameter: (String) -> Unit,
    onDone: () -> Unit,
    commandTitle: String = "",
    onAuthorization: (Int) -> Unit = {},
    targets: List<String> = emptyList(),
    variables: List<VariableOption> = emptyList(),
    showDone: Boolean = true,
    canConfigureOutputs: Boolean = false,
    onConfigureOutputs: (() -> Unit)? = null,
    onValidity: (Boolean) -> Unit = {},
) {
    val meta = item.typeId?.let { id -> com.flowglass.engine.BuiltInComponents.registry().find(id) }
    if (meta == null || meta.params.isEmpty()) {
        if (showDone) EditorPill("完成", "完成", action = onDone)
        return
    }

    if (meta.params.size > 1) {
        if (item.typeId == "condition.variable") {
            VariableConditionEditor(meta, item, onParameter, variables, onValidity)
        } else if (item.typeId == "action.set_var") {
            SetVariableEditor(meta, item, onParameter, targets, variables, onValidity)
        } else {
            MultiParameterEditor(meta, item, onParameter, onAuthorization, targets, variables,
                canConfigureOutputs, onConfigureOutputs)
        }
    } else {
        val spec = meta.params.single()
        ParamField(
            spec = spec,
            current = item.parameter,
            tag = "item-parameter",
            onValue = onParameter,
            targets = targets,
            variables = variables,
            canConfigureOutputs = canConfigureOutputs,
            onConfigureOutputs = onConfigureOutputs,
        )
        if (spec.hint.isNotBlank()) {
            T(spec.hint, 13.sp, color = C.OnSurfaceVar, lineHeight = 20.sp,
                modifier = Modifier.testTag("parameter-help"))
        } else {
            val default = runCatching { ParameterCodec.format(spec, spec.defaultValue) }.getOrDefault("")
            if (default.isNotEmpty()) {
                T("示例：$default", 13.sp, color = C.OnSurfaceVar, lineHeight = 20.sp,
                    modifier = Modifier.testTag("parameter-help"))
            }
        }
    }
    if (showDone) EditorPill("完成", "完成", action = onDone)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetVariableEditor(
    meta: ComponentMeta,
    item: CommandItem,
    onParameter: (String) -> Unit,
    targets: List<String>,
    variables: List<VariableOption>,
    onValidity: (Boolean) -> Unit = {},
) {
    var fields by remember(item.id) {
        mutableStateOf(
            ParameterForm.fields(meta, item.parameter).let { initial ->
                if (variableTypeOfField(initial["type"].orEmpty()) != com.flowglass.engine.VariableType.INT) {
                    initial + ("op" to "set")
                } else initial
            },
        )
    }
    var error by remember(item.id) { mutableStateOf<String?>(null) }

    fun nameProblem(next: Map<String, String>): String? {
        val candidate = next["name"].orEmpty().trim().removeSurrounding("{", "}")
        if (candidate.isEmpty()) return "变量名不能为空"
        com.flowglass.engine.VariableName.error(candidate)?.let { return it }
        val scope = when (next["scope"].orEmpty()) {
            "所有指令", "global" -> "global"
            else -> "local"
        }
        val sameScope = variables.filter { it.scope.name.lowercase() == scope }
        val exists = sameScope.any { it.name == candidate }
        return if (!exists && variables.any { it.name == candidate }) "名字已被占用：$candidate" else null
    }

    fun update(key: String, value: String) {
        val next = fields + (key to value)
        fields = next
        val composed = ParameterForm.compose(meta, next)
            .onSuccess { onParameter(it); error = null }
            .onFailure { error = it.message?.replace('\n', ' ')?.take(120) }
        onValidity(composed.isFailure || nameProblem(next) != null)
    }

    fun selectType(next: com.flowglass.engine.VariableType) {
        val nextFields = fields + ("type" to next.storageKey) +
            if (next == com.flowglass.engine.VariableType.INT) emptyMap() else mapOf("op" to "set")
        fields = nextFields
        val result = ParameterForm.compose(meta, nextFields)
        result
            .onSuccess { onParameter(it); error = null }
            .onFailure { error = it.message?.replace('\n', ' ')?.take(120) }
        onValidity(result.isFailure || nameProblem(nextFields) != null)
    }
    val name = fields["name"].orEmpty().trim().removeSurrounding("{", "}")
    val exists = name.isNotEmpty() && variables.any {
        it.scope != com.flowglass.engine.VariableScope.BUILTIN && it.name == name
    }
    val scope = when (fields["scope"].orEmpty()) {
        "所有指令", "global" -> "global"
        else -> "local"
    }
    val type = variableTypeOfField(fields["type"].orEmpty())
    LaunchedEffect(item.id) {
        onValidity(ParameterForm.compose(meta, fields).isFailure || nameProblem(fields) != null)
    }
    val nameError = com.flowglass.engine.VariableName.error(name)
        ?: if (!exists && variables.any { it.name == name }) "名字已被占用：$name" else null

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        T("变量名", 14.sp, FontWeight.SemiBold)
        ParameterTextField(name, "param-name") { update("name", it) }
        T("只填名字，例如 城市。已有变量直接改名即可；新变量会自动创建。", 12.sp,
            color = C.OnSurfaceVar, lineHeight = 18.sp)
        nameError?.let { T(it, 12.sp, color = C.Bad) }
        if (error != null) T("参数无效：$error", 12.sp, color = C.Bad)

        for (spec in meta.params) {
            if (spec.key == "name") continue
            if (exists && (spec.key == "scope" || spec.key == "retain")) continue
            if (spec.key == "retain" && scope != "local") continue
            if (spec.key == "op" && type != com.flowglass.engine.VariableType.INT) {
                T("操作", 14.sp, FontWeight.SemiBold)
                T("设为", 14.sp, color = C.OnSurfaceVar)
                continue
            }
            if (spec.key == "type") {
                T("类型", 14.sp, FontWeight.SemiBold)
                VariableTypePicker(type, onSelect = { selectType(it) }, tagPrefix = "set-var-type")
                continue
            }
            if (spec.key == "value") {
                T("内容", 14.sp, FontWeight.SemiBold)
                VariableTextField(fields[spec.key].orEmpty(), "param-value", variables, multiline = type.isList,
                    onValue = { update(spec.key, it) })
                T(
                    if (type.isList) "列表用英文逗号或换行分隔，也接受 [1,2,3]。" else "支持 {变量}。",
                    12.sp, color = C.OnSurfaceVar,
                )
                continue
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(ParameterForm.fieldLabel(spec), 14.sp, FontWeight.SemiBold)
                ParamField(
                    spec = spec,
                    current = fields[spec.key].orEmpty(),
                    tag = "param-${spec.key}",
                    onValue = { update(spec.key, it) },
                    targets = targets,
                    variables = variables,
                )
                if (spec.hint.isNotBlank()) T(spec.hint, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiParameterEditor(
    meta: ComponentMeta,
    item: CommandItem,
    onParameter: (String) -> Unit,
    onAuthorization: (Int) -> Unit,
    targets: List<String> = emptyList(),
    variables: List<VariableOption> = emptyList(),
    canConfigureOutputs: Boolean = false,
    onConfigureOutputs: (() -> Unit)? = null,
    onValidity: (Boolean) -> Unit = {},
) {
    var fields by remember(item.id) { mutableStateOf(ParameterForm.fields(meta, item.parameter)) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }

    fun nameProblem(next: Map<String, String>): String? {
        val candidate = next["name"].orEmpty().trim().removeSurrounding("{", "}")
        if (candidate.isEmpty()) return "变量名不能为空"
        com.flowglass.engine.VariableName.error(candidate)?.let { return it }
        val scope = when (next["scope"].orEmpty()) {
            "所有指令", "global" -> "global"
            else -> "local"
        }
        val sameScope = variables.filter { it.scope.name.lowercase() == scope }
        val exists = sameScope.any { it.name == candidate }
        return if (!exists && variables.any { it.name == candidate }) "名字已被占用：$candidate" else null
    }

    fun update(key: String, value: String) {
        val next = fields + (key to value)
        fields = next
        val composed = ParameterForm.compose(meta, next)
            .onSuccess { onParameter(it); error = null; onValidity(false) }
            .onFailure { error = it.message?.replace('\n', ' ')?.take(120); onValidity(true) }
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
            ParamField(
                spec = spec,
                current = fields[spec.key].orEmpty(),
                tag = "param-${spec.key}",
                onValue = { update(spec.key, it) },
                targets = targets,
                variables = variables,
                canConfigureOutputs = canConfigureOutputs,
                onConfigureOutputs = onConfigureOutputs,
            )
            if (spec.hint.isNotBlank()) T(spec.hint, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun ParameterTextField(value: String, tag: String, onValue: (String) -> Unit) {
    BasicTextField(value, onValue,
        Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag).imeAutoScroll()
            .clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
        textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 24.sp),
        cursorBrush = SolidColor(C.Primary))
}

@Composable
internal fun PercentSlider(value: Float, tag: String = "parameter-percent-slider", onValueChange: (Float) -> Unit) {
    val currentChange by rememberUpdatedState(onValueChange)
    val radius = with(LocalDensity.current) { 10.dp.toPx() }
    Canvas(Modifier.fillMaxWidth().height(56.dp).testTag(tag)
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
