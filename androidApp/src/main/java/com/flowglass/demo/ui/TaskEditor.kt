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
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.OnError
import com.flowglass.engine.ParamSpec
import com.flowglass.engine.ParamType
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/**
 * 任务编辑页：步骤列表 + 步骤参数表单。
 * 执行语义见 docs/task-flow.md：顺序执行、失败策略、%{var} 读取时展开。
 */
@Composable
internal fun TaskEditorHost(state: AppState) {
    val cmd = state.taskEditing?.let(state::find)
    var last by remember { mutableStateOf<Cmd?>(null) }
    if (cmd != null) last = cmd
    val shown = cmd ?: last
    AnimatedVisibility(cmd != null,
        enter = fadeIn(effect()) + scaleIn(spatial(), initialScale = 0.96f),
        exit = fadeOut(effect()) + scaleOut(spatial(), targetScale = 0.96f)) {
        if (shown != null) key(shown.id) { TaskEditorPage(state, shown) }
    }
}

@Composable
private fun TaskEditorPage(state: AppState, cmd: Cmd) {
    val item = state.actionEditing?.let { id -> cmd.actions.firstOrNull { it.id == id } }
    BackHandler(enabled = state.taskEditing == cmd.id) {
        if (state.actionEditing != null) state.actionEditing = null
        else {
            state.actionEditing = null
            state.taskEditing = null
        }
    }
    if (item != null) ActionEditorPage(state, cmd, item) else TaskStepsPage(state, cmd)
}

@Composable
private fun TaskStepsPage(state: AppState, cmd: Cmd) {
    var adding by remember(cmd.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(C.Bg).testTag("task-editor")
        .statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EditorIconButton(Ic.Back, "返回指令编辑") {
                state.actionEditing = null
                state.taskEditing = null
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(if (adding) "添加动作" else "任务", 26.sp, FontWeight.Bold)
                T(
                    if (adding) "选择一个动作加入任务末尾"
                    else "${cmd.actions.size} 个步骤 · 按顺序执行",
                    13.sp, color = C.OnSurfaceVar,
                )
            }
            if (adding) EditorPill("返回", "返回任务列表") { adding = false }
            else EditorPill("添加", "添加动作") { adding = true }
        }
        if (adding) {
            TaskAddPanel(state, cmd) { adding = false }
        } else {
            if (cmd.actions.isEmpty()) {
                T("还没有动作。点右上角「添加」选择要执行的动作。", 14.sp, color = C.Muted,
                    modifier = Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(18.dp))
            }
            cmd.actions.forEachIndexed { index, item ->
                key(item.id) {
                    Row(Modifier.fillMaxWidth().testTag("task-step-${item.id}").staggerIn(index)
                        .clip(RoundedRectangle(24.dp)).background(C.Card)
                        .editorAction("编辑第${index + 1}步：${item.name}") { state.actionEditing = item.id }
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(Capsule()).background(C.PrimarySoft), contentAlignment = Alignment.Center) {
                            T("${index + 1}", 13.sp, FontWeight.Bold, C.Primary, tabular = true)
                        }
                        Spacer(Modifier.width(10.dp))
                        LineIcon(item.ic, C.Primary, 20.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            T(item.name, 15.sp, FontWeight.SemiBold, maxLines = 1)
                            T(itemSummary(item), 12.sp, color = C.OnSurfaceVar, maxLines = 2)
                            if (item.onError != OnError.STOP) {
                                T(item.onError.label, 11.sp, FontWeight.SemiBold, C.Primary)
                            }
                        }
                        if (index > 0) EditorIconButton(Ic.ArrowUp, "上移第${index + 1}步") {
                            state.moveItem(cmd.id, CommandSection.ACTIONS, item.id, -1)
                        }
                        if (index < cmd.actions.lastIndex) EditorIconButton(Ic.ArrowDown, "下移第${index + 1}步") {
                            state.moveItem(cmd.id, CommandSection.ACTIONS, item.id, 1)
                        }
                        EditorIconButton(Ic.Trash, "删除第${index + 1}步") {
                            state.removeItem(cmd.id, CommandSection.ACTIONS, item.id)
                        }
                    }
                }
            }
            if (cmd.actions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T("执行规则", 14.sp, FontWeight.SemiBold)
                    T("从上到下依次执行；失败策略默认「失败即停」，也可以在步骤里改成继续或重试一次。", 12.sp, color = C.OnSurfaceVar)
                    T("后续步骤可以用 %{last} 引用上一步结果，或 %{shell.stdout}、%{http.body} 引用动作输出。", 12.sp, color = C.OnSurfaceVar)
                }
            }
        }
    }
}

private fun itemSummary(item: CommandItem): String {
    val text = item.parameter.let {
        if (item.typeId == "action.post_notification" && it.startsWith('|')) it.substring(1) else it
    }
    return if (text.isBlank()) "点击设置参数" else text.replace('\n', ' ').take(80)
}

@Composable
private fun TaskAddPanel(state: AppState, cmd: Cmd, onDone: () -> Unit) {
    val activeTier = if (state.root?.granted == true) com.flowglass.engine.Tier.ROOT else com.flowglass.engine.Tier.NORMAL
    var rowIndex = 0
    CommandCatalog.options(CommandSection.ACTIONS).groupBy { it.categoryName }.forEach { (category, options) ->
        T(category, 13.sp, FontWeight.Bold, C.Muted, modifier = Modifier.padding(top = 8.dp, start = 6.dp))
        options.forEach { option ->
            val locked = option.minTier.ordinal > activeTier.ordinal
            Row(Modifier.fillMaxWidth().testTag("task-add-${option.typeId}").staggerIn(rowIndex++.coerceAtMost(8))
                .clip(RoundedRectangle(24.dp)).background(C.Card)
                .then(if (locked) Modifier else Modifier.editorAction("添加动作：${option.name}") {
                    state.addItem(cmd.id, CommandSection.ACTIONS, option)
                    onDone()
                })
                .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                LineIcon(option.ic, if (locked) C.Muted else C.Primary, 22.dp)
                Spacer(Modifier.width(12.dp))
                T(option.name, 16.sp, color = if (locked) C.Muted else C.OnSurface, modifier = Modifier.weight(1f))
                if (locked) T("需要 Root", 12.sp, color = C.Muted) else LineIcon(Ic.Plus, C.Primary, 20.dp)
            }
        }
    }
}

@Composable
private fun TaskReplaceList(state: AppState, cmd: Cmd, item: CommandItem, onDone: () -> Unit) {
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

@Composable
private fun ActionEditorPage(state: AppState, cmd: Cmd, item: CommandItem) {
    val meta = item.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it) }
    var fields by remember(item.id) { mutableStateOf(meta?.let { ParameterForm.fields(it, item.parameter) } ?: emptyMap()) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }

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

    Column(Modifier.fillMaxSize().background(C.Bg).testTag("action-editor")
        .statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EditorIconButton(Ic.Back, "返回任务列表") { state.actionEditing = null }
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
            var replacing by remember(item.id) { mutableStateOf(false) }
            if (!replacing) {
                EditorPill("换成目录动作", "替换为目录动作") { replacing = true }
            } else {
                T("选择新动作：当前步骤的参数会被替换成它的默认值。", 12.sp, color = C.OnSurfaceVar)
                TaskReplaceList(state, cmd, item) { replacing = false }
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
        EditorPill("完成", "完成编辑参数") { state.actionEditing = null }
        Spacer(Modifier.height(8.dp))
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
