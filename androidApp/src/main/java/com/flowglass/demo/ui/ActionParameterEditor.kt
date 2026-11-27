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
    if (section == CommandSection.TRIGGERS) {
        T("触发器参数不支持变量；这里只按固定值匹配。", 12.sp, color = C.OnSurfaceVar,
            lineHeight = 18.sp, modifier = Modifier.testTag("trigger-parameter-hint"))
    }

    if (meta.params.size > 1) {
        if (item.typeId == "condition.variable") {
            VariableConditionEditor(meta, item, onParameter, variables, onValidity)
        } else if (item.typeId == "action.set_var") {
            SetVariableEditor(meta, item, onParameter, targets, variables, onValidity)
        } else {
            MultiParameterEditor(meta, item, onParameter, onAuthorization, targets,
                if (section == CommandSection.TRIGGERS) emptyList() else variables,
                canConfigureOutputs, onConfigureOutputs, onValidity)
        }
    } else {
        val spec = meta.params.single()
        var problem by remember(item.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(item.id) {
            problem = ParameterForm.triggerProblem(meta, mapOf(spec.key to item.parameter))
            onValidity(problem != null || ParameterForm.compose(meta, mapOf(spec.key to item.parameter)).isFailure)
        }
        ParamField(
            spec = spec,
            current = item.parameter,
            tag = "item-parameter",
            onValue = { raw ->
                onParameter(raw)
                problem = ParameterForm.triggerProblem(meta, mapOf(spec.key to raw))
                onValidity(problem != null || ParameterForm.compose(meta, mapOf(spec.key to raw)).isFailure)
            },
            targets = targets,
            variables = if (section == CommandSection.TRIGGERS) emptyList() else variables,
            canConfigureOutputs = canConfigureOutputs,
            onConfigureOutputs = onConfigureOutputs,
        )
        problem?.let { T(it, 12.sp, color = C.Bad) }
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
        // Same-name local shadowing a global is legal; only a name that is neither editable nor
        // already a user variable is occupied.
        val exists = variables.any {
            it.scope != com.flowglass.engine.VariableScope.BUILTIN &&
                it.scope != com.flowglass.engine.VariableScope.OUTPUT && it.name == candidate
        }
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
        it.scope != com.flowglass.engine.VariableScope.BUILTIN &&
            it.scope != com.flowglass.engine.VariableScope.OUTPUT && it.name == name
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
    LaunchedEffect(item.id) {
        val problem = ParameterForm.triggerProblem(meta, fields)
        error = problem
        onValidity(problem != null || ParameterForm.compose(meta, fields).isFailure)
    }

    fun update(key: String, value: String) {
        val next = fields + (key to value)
        fields = next
        val composed = ParameterForm.compose(meta, next)
        composed
            .onSuccess {
                val problem = ParameterForm.triggerProblem(meta, next)
                error = problem
                onParameter(it)
                onValidity(problem != null)
            }
            .onFailure {
                error = it.message?.replace('\n', ' ')?.take(120)
                onValidity(true)
            }
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

    val params = visibleParams(meta)
    if (meta.typeId == "trigger.http_request") {
        val byKey = params.associateBy { it.key }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TriggerSection("监听", listOfNotNull(byKey["port"], byKey["bind"])) { spec ->
                TriggerParamBlock(spec, fields, targets, variables, canConfigureOutputs,
                    onConfigureOutputs, ::update)
            }
            TriggerSection("请求匹配", listOfNotNull(byKey["method"], byKey["path"])) { spec ->
                TriggerParamBlock(spec, fields, targets, variables, canConfigureOutputs,
                    onConfigureOutputs, ::update)
            }
        }
    } else if (meta.typeId == "trigger.intent_received") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TriggerSection("匹配条件", params) { spec ->
                TriggerParamBlock(spec, fields, targets, variables, canConfigureOutputs,
                    onConfigureOutputs, ::update)
            }
        }
    } else {
        params.forEach { spec ->
            TriggerParamBlock(spec, fields, targets, variables, canConfigureOutputs,
                onConfigureOutputs, ::update)
        }
    }
    if (meta.kind == com.flowglass.engine.ComponentKind.TRIGGER &&
        meta.params.any { it.key == "matchMode" }
    ) {
        TriggerMatchPreview(meta, fields)
    }
}

private fun visibleParams(meta: ComponentMeta): List<com.flowglass.engine.ParamSpec> =
    if (meta.kind == com.flowglass.engine.ComponentKind.TRIGGER &&
        meta.typeId in setOf("trigger.intent_received", "trigger.http_request")
    ) {
        // Token is legacy-only for these two open entries; it must not render a subtitle or box.
        meta.params.filterNot { it.key == "token" }
    } else {
        meta.params
    }

@Composable
private fun TriggerSection(
    title: String,
    params: List<com.flowglass.engine.ParamSpec>,
    content: @Composable (com.flowglass.engine.ParamSpec) -> Unit,
) {
    if (params.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title.isNotBlank()) {
            T(title, 12.sp, FontWeight.SemiBold, color = C.OnSurfaceVar)
        }
        params.forEach { content(it) }
    }
}

@Composable
private fun TriggerParamBlock(
    spec: com.flowglass.engine.ParamSpec,
    fields: Map<String, String>,
    targets: List<String>,
    variables: List<VariableOption>,
    canConfigureOutputs: Boolean,
    onConfigureOutputs: (() -> Unit)?,
    update: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        if (spec.hint.isNotBlank()) {
            T(spec.hint, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun TriggerMatchPreview(meta: ComponentMeta, fields: Map<String, String>) {
    var sample by remember(meta.typeId) { mutableStateOf("") }
    val keys = when {
        meta.params.any { it.key == "title" } -> listOf("title", "text", "subtext")
        meta.params.any { it.key == "keyword" } -> listOf("keyword")
        meta.params.any { it.key == "ssid" } -> listOf("ssid")
        meta.params.any { it.key == "name" } -> listOf("name")
        else -> emptyList()
    }
    if (keys.isEmpty()) return
    val mode = fields["matchMode"].orEmpty()
    val regex = mode == "regex" || mode == "正则表达式"
    val exact = mode == "exact" || mode == "精确"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T("测试匹配（本地预览）", 14.sp, FontWeight.SemiBold)
        T("粘贴一段示例文本；只在本页计算，不读取真实通知、短信或设备名称。", 12.sp,
            color = C.OnSurfaceVar, lineHeight = 18.sp)
        ParameterTextField(sample, "trigger-match-sample") { sample = it }
        val rows = keys.mapNotNull { key ->
            val pattern = fields[key].orEmpty().trim()
            if (pattern.isEmpty()) null else {
                val hit = runCatching {
                    when {
                        regex -> Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(sample)
                        exact -> sample.equals(pattern, ignoreCase = true)
                        else -> sample.contains(pattern, ignoreCase = true)
                    }
                }.getOrDefault(false)
                val label = meta.params.firstOrNull { it.key == key }?.label ?: key
                "$label：${if (hit) "命中" else "未命中"}"
            }
        }
        T(if (rows.isEmpty()) "先填写一个关键字再测试。" else rows.joinToString(" · "), 12.sp,
            color = if (rows.any { it.endsWith("：命中") }) C.Primary else C.OnSurfaceVar,
            lineHeight = 18.sp)
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
