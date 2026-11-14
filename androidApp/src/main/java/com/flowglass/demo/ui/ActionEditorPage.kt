package com.flowglass.demo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
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
import com.flowglass.engine.OnError
import com.flowglass.engine.ParamSpec
import com.flowglass.engine.ParamType
import com.kyant.shapes.RoundedRectangle

/**
 * 动作参数页：从指令编辑页的任务行直接进入，按 ParamSpec 生成表单。
 * 排序、增删仍在指令编辑页完成；执行语义见 docs/task-flow.md。
 */
@Composable
internal fun ActionEditorHost(state: AppState) {
    val cmd = state.actionEditingCmd?.let(state::find)
    val item = cmd?.actions?.firstOrNull { it.id == state.actionEditing }
    var lastCmd by remember { mutableStateOf<Cmd?>(null) }
    var lastItem by remember { mutableStateOf<CommandItem?>(null) }
    if (cmd != null && item != null) {
        lastCmd = cmd
        lastItem = item
    }
    val show = cmd != null && item != null
    AnimatedVisibility(show,
        enter = fadeIn(effect()) + scaleIn(spatial(), initialScale = 0.96f),
        exit = fadeOut(effect()) + scaleOut(spatial(), targetScale = 0.96f)) {
        val shownCmd = cmd ?: lastCmd
        val shownItem = item ?: lastItem
        if (shownCmd != null && shownItem != null) {
            key(shownItem.id) { ActionEditorPage(state, shownCmd, shownItem) }
        }
    }
}

@Composable
private fun ActionEditorPage(state: AppState, cmd: Cmd, item: CommandItem) {
    val meta = item.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it) }
    var fields by remember(item.id) { mutableStateOf(meta?.let { ParameterForm.fields(it, item.parameter) } ?: emptyMap()) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var replacing by remember(item.id) { mutableStateOf(false) }

    fun close() {
        state.actionEditing = null
        state.actionEditingCmd = null
    }

    fun update(key: String, value: String) {
        val metadata = meta ?: return
        val next = fields + (key to value)
        fields = next
        ParameterForm.compose(metadata, next)
            .onSuccess {
                state.setItemParameter(cmd.id, CommandSection.ACTIONS, item.id, it)
                error = null
            }
            .onFailure { error = it.message?.replace('\n', ' ')?.take(120) }
    }

    BackHandler(enabled = true) { close() }

    Column(Modifier.fillMaxSize().background(C.Bg).testTag("action-editor")
        .statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EditorIconButton(Ic.Back, "返回指令编辑") { close() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(item.name, 24.sp, FontWeight.Bold, maxLines = 2)
                T(meta?.category ?: "未知动作", 12.sp, color = C.OnSurfaceVar)
            }
            LineIcon(item.ic, C.Primary, 26.dp)
        }
        error?.let {
            T("参数无效：$it", 13.sp, color = C.Bad,
                modifier = Modifier.fillMaxWidth().testTag("action-error")
                    .clip(RoundedRectangle(20.dp)).background(C.Card).padding(12.dp))
        }
        if (meta == null) {
            T("这是早期保存的动作，没有参数定义。可以修改原始文本，或换成目录里的动作。", 13.sp, color = C.OnSurfaceVar)
            ParameterTextField(item.parameter, "item-parameter") { value ->
                state.setItemParameter(cmd.id, CommandSection.ACTIONS, item.id, value)
            }
            if (!replacing) {
                EditorPill("换成目录动作", "替换为目录动作") { replacing = true }
            } else {
                T("选择新动作：当前步骤的参数会被替换成它的默认值。", 12.sp, color = C.OnSurfaceVar)
                ReplaceActionList(state, cmd, item) { replacing = false }
            }
        } else if (meta.params.isEmpty()) {
            T("该动作没有可配置参数。", 13.sp, color = C.OnSurfaceVar)
        } else {
            meta.params.forEach { spec ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T(ParameterForm.fieldLabel(spec), 14.sp, FontWeight.SemiBold)
                    ParamField(spec, fields[spec.key].orEmpty(), item) { update(spec.key, it) }
                    if (spec.hint.isNotBlank()) T(spec.hint, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T("失败策略", 14.sp, FontWeight.SemiBold)
            SegmentedSelector(
                OnError.entries.map { Ic.Shield to it.label },
                OnError.entries.indexOf(item.onError).coerceAtLeast(0),
            ) { state.setItemOnError(cmd.id, CommandSection.ACTIONS, item.id, OnError.entries[it]) }
            T("失败即停：后续步骤跳过；继续执行：记下失败继续往下；重试一次：同一参数再执行一次。", 12.sp, color = C.OnSurfaceVar)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T("可用变量", 13.sp, FontWeight.SemiBold)
            T("%{last} 上一步结果", 12.sp, color = C.OnSurfaceVar)
            T("%{shell.stdout} 执行 Shell 的标准输出", 12.sp, color = C.OnSurfaceVar)
            T("%{http.body} HTTP 请求的响应体", 12.sp, color = C.OnSurfaceVar)
            T("写在文本参数里即可，执行时替换；未定义的变量会原样保留。", 12.sp, color = C.Muted)
        }
        EditorPill("完成", "完成编辑参数") { close() }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ReplaceActionList(state: AppState, cmd: Cmd, item: CommandItem, onDone: () -> Unit) {
    val activeTier = if (state.root?.granted == true) com.flowglass.engine.Tier.ROOT else com.flowglass.engine.Tier.NORMAL
    var rowIndex = 0
    CommandCatalog.options(CommandSection.ACTIONS).groupBy { it.categoryName }.forEach { (category, options) ->
        T(category, 13.sp, FontWeight.Bold, C.Muted, modifier = Modifier.padding(top = 8.dp, start = 6.dp))
        options.forEach { option ->
            val locked = option.minTier.ordinal > activeTier.ordinal
            Row(Modifier.fillMaxWidth().testTag("task-replace-${option.typeId}").staggerIn(rowIndex++.coerceAtMost(8))
                .clip(RoundedRectangle(24.dp)).background(C.Card)
                .then(if (locked) Modifier else Modifier.editorAction("替换为：${option.name}") {
                    state.replaceItem(cmd.id, CommandSection.ACTIONS, item.id, option)
                    onDone()
                })
                .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                LineIcon(option.ic, if (locked) C.Muted else C.Primary, 22.dp)
                Spacer(Modifier.width(12.dp))
                T(option.name, 16.sp, color = if (locked) C.Muted else C.OnSurface, modifier = Modifier.weight(1f))
                if (locked) T("需要 Root", 12.sp, color = C.Muted) else LineIcon(Ic.ChevronRight, C.Muted, 18.dp)
            }
        }
    }
}

private val OnError.label: String
    get() = when (this) {
        OnError.STOP -> "失败即停"
        OnError.CONTINUE -> "继续执行"
        OnError.RETRY -> "重试一次"
    }

@Composable
private fun ParamField(spec: ParamSpec, value: String, item: CommandItem, onValue: (String) -> Unit) {
    when (spec.type) {
        ParamType.TRI_STATE -> {
            val options = spec.options.ifEmpty { com.flowglass.engine.ParameterCodec.switchOptions }
            val labels = options.map { it.label }
            SegmentedSelector(options.map { item.ic to it.label }, labels.indexOf(value).coerceAtLeast(0)) {
                onValue(labels[it])
            }
        }
        ParamType.ENUM -> {
            val labels = spec.options.map { it.label }
            if (labels.isEmpty()) {
                ParameterTextField(value, "param-${spec.key}", onValue)
            } else if (labels.size <= 3) {
                SegmentedSelector(spec.options.map { item.ic to it.label }, labels.indexOf(value).coerceAtLeast(0)) {
                    onValue(labels[it])
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    spec.options.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { option ->
                                val selected = option.label == value
                                Box(Modifier.weight(1f).clip(RoundedRectangle(20.dp))
                                    .background(if (selected) C.PrimarySoft else C.Card)
                                    .editorAction("选项：${option.label}") { onValue(option.label) }
                                    .padding(12.dp), contentAlignment = Alignment.Center) {
                                    T(option.label, 14.sp, color = if (selected) C.Primary else C.OnSurface)
                                }
                            }
                            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        ParamType.PERCENTAGE -> {
            val number = value.removeSuffix("%").trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T(value.ifBlank { "0%" }, 20.sp, FontWeight.Bold, tabular = true)
                PercentSlider(number.toFloat()) { onValue("${it.toInt().coerceIn(0, 100)}%") }
                ParameterTextField(value, "param-${spec.key}", onValue)
            }
        }
        ParamType.INT_RANGE, ParamType.TIME_RANGE -> {
            val parts = value.split(Regex("\\s*[–—~-]\\s*"), limit = 2)
            val from = parts.getOrElse(0) { "" }
            val to = parts.getOrElse(1) { "" }
            val numeric = spec.type == ParamType.INT_RANGE
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ParameterTextField(from, "param-${spec.key}-from",
                        if (numeric) KeyboardType.Number else KeyboardType.Text) { onValue("$it – $to".trim()) }
                }
                T("至", 13.sp, color = C.Muted)
                Box(Modifier.weight(1f)) {
                    ParameterTextField(to, "param-${spec.key}-to",
                        if (numeric) KeyboardType.Number else KeyboardType.Text) { onValue("$from – $it".trim()) }
                }
            }
        }
        ParamType.APPLICATION -> ApplicationParameterPicker(item.copy(parameter = value), onValue)
        ParamType.MULTILINE_TEXT -> BasicTextField(value, onValue,
            Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("param-${spec.key}")
                .clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
            textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 24.sp),
            cursorBrush = SolidColor(C.Primary))
        ParamType.INTEGER -> ParameterTextField(value, "param-${spec.key}", KeyboardType.Number, onValue)
        ParamType.DURATION, ParamType.TIME, ParamType.TEXT -> ParameterTextField(value, "param-${spec.key}", onValue)
    }
}

@Composable
private fun ParameterTextField(
    value: String,
    tag: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onValue: (String) -> Unit,
) {
    BasicTextField(value, onValue,
        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
            .clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
        textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 24.sp),
        cursorBrush = SolidColor(C.Primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
