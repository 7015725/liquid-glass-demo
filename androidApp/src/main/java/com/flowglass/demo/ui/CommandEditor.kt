package com.flowglass.demo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.sp
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.demo.system.trigger.listenState
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt
import androidx.compose.ui.state.ToggleableState
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/** Editor UI; native execution lifetime is owned by ExecutionRuntime. */
@Composable
fun CommandEditorHost(state: AppState, onAuthorization: (Int) -> Unit = { page ->
    state.closeEditor()
    state.settingsPage = page
}) {
    val scope = rememberCoroutineScope()
    val cmd = state.editing?.let(state::find)
    var last by remember { mutableStateOf<Cmd?>(null) }
    if (cmd != null) last = cmd
    val shown = cmd ?: last
    AnimatedVisibility(cmd != null,
        enter = fadeIn(effect()) + scaleIn(spatial(), initialScale = 0.96f),
        exit = fadeOut(effect()) + scaleOut(spatial(), targetScale = 0.96f)) {
        if (shown != null) key(shown.id) { CommandEditor(state, shown, scope, onAuthorization) }
    }
}

@Composable
private fun CommandEditor(state: AppState, cmd: Cmd, scope: CoroutineScope, onAuthorization: (Int) -> Unit) {
    RefreshEditorRoot(state, cmd)
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = com.flowglass.demo.system.SysTick.tick
    val issues = state.validationIssues(cmd)
    var panel by remember { mutableStateOf<EditorPanel?>(null) }
    val backdrop = rememberLayerBackdrop()
    fun back() { if (panel != null) panel = null else state.closeEditor() }
    BackHandler(enabled = state.editing == cmd.id) { back() }
    Box(Modifier.fillMaxSize().background(C.Bg).testTag("command-editor")
        .pointerInput(Unit) { detectTapGestures { } }) {
    Column(Modifier.fillMaxSize().layerBackdrop(backdrop)
        .then(if (panel != null) Modifier.clearAndSetSemantics { } else Modifier)
        .statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EditorIconButton(Ic.Back, "返回") { back() }
            Spacer(Modifier.weight(1f))
            if (cmd.auto) {
                val toggleBackdrop = rememberLayerBackdrop()
                Box(Modifier.clip(Capsule()).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().layerBackdrop(toggleBackdrop).background(C.Card))
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(if (cmd.enabled) "已开启" else "已关闭", 14.sp, color = C.OnSurfaceVar)
                        Spacer(Modifier.width(12.dp))
                        LiquidToggle({ cmd.enabled }, {
                            if (!cmd.enabled && issues.isNotEmpty()) state.editorNotice = issues.first()
                            else PermissionGate.request(state, cmd, context)
                        }, toggleBackdrop,
                            Modifier.testTag("editor-toggle").semantics {
                                contentDescription = "启用指令"
                                toggleableState = if (cmd.enabled) ToggleableState.On else ToggleableState.Off
                                onClick { PermissionGate.request(state, cmd, context); true }
                            })
                    }
                }
            }
        }
        Box(Modifier.size(76.dp).clip(PolygonShape(M3EShapes.Clover4)).background(C.PrimarySoft)
            .editorAction("选择图标") { panel = EditorPanel.Icons }, contentAlignment = Alignment.Center) {
            LineIcon(cmd.ic, C.Primary, 34.dp)
        }
        StorageErrorNotice(state.storageError)
        state.editorNotice?.let { notice ->
            NoticeCard(notice, C.Mid, C.MidSoft) { state.editorNotice = null }
        }
        if (issues.isNotEmpty()) {
            NoticeCard(
                "任务还不完整：" + issues.take(3).joinToString("；") +
                    if (issues.size > 3) "；等 ${issues.size} 项" else "",
                C.Bad, C.BadSoft,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            EditorPill("变量", "编辑指令和全局变量") { panel = EditorPanel.Variables }
            val locals = state.localsOf(cmd.id)
            T(
                "指令变量 ${locals.size} · 全局 ${state.engineGlobals.size}",
                12.sp, color = C.Muted,
            )
        }
        BasicTextField(cmd.title, { state.setTitle(cmd.id, it) },
            Modifier.fillMaxWidth().testTag("editor-name"),
            textStyle = TextStyle(color = C.OnSurface, fontSize = 32.sp, fontWeight = FontWeight.Bold),
            cursorBrush = SolidColor(C.Primary),
            decorationBox = { inner -> Box { if (cmd.title.isEmpty()) T("指令名称", 32.sp, color = C.Muted); inner() } })
        BasicTextField(cmd.desc, { state.setDescription(cmd.id, it) },
            Modifier.fillMaxWidth().testTag("editor-description"),
            textStyle = TextStyle(color = C.OnSurfaceVar, fontSize = 15.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(C.Primary),
            decorationBox = { inner -> Box { if (cmd.desc.isEmpty()) T("添加描述", 15.sp, color = C.Muted); inner() } })
        if (cmd.auto) {
            val listen = cmd.listenState(state.running, context)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("监听状态", 13.sp, color = C.OnSurfaceVar)
                Spacer(Modifier.width(8.dp))
                ListenStatePill(listen)
            }
        }
        MissingPermissionNotice(cmd) { onAuthorization(SettingsPage.Permissions) }
        val exec = state.executing[cmd.id] ?: 0
        ExecButton(exec, Modifier.fillMaxWidth().testTag("editor-execute"), large = true,
            idleLabel = if (cmd.auto) "立即运行" else "一键执行",
            failureLabel = if (state.executionMessages[cmd.id] == "执行环境未就绪") "执行环境未就绪" else "失败",
            onClick = { state.execute(cmd, scope, if (cmd.auto) "手动测试" else "手动执行") },
            onStop = if (exec == 1) ({ state.stopExecution(cmd.id) }) else null)
        if (cmd.auto) T("跳过触发器和条件，直接执行任务", 12.sp, color = C.Muted)
        val sections = if (cmd.auto) CommandSection.entries else listOf(CommandSection.ACTIONS)
        sections.forEachIndexed { i, section ->
            Column(Modifier.fillMaxWidth().testTag("section-${section.name}")
                .staggerIn(i).clip(com.kyant.shapes.RoundedRectangle(28.dp)).background(C.Card).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T(section.label, 20.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    T("${if (section == CommandSection.ACTIONS) cmd.actions.allItems().size else cmd.items(section).size}", 13.sp, color = C.Muted)
                    Spacer(Modifier.width(10.dp))
                    EditorPill("添加", "添加${section.label}") { panel = EditorPanel.Add(section) }
                }
                if (cmd.items(section).isEmpty()) T("尚未添加${section.label}", 14.sp, color = C.Muted)
                if (section == CommandSection.ACTIONS) {
                    cmd.actions.forEachIndexed { index, item ->
                        key(item.id) {
                            ActionTreeRow(state, cmd, item, 0, index, cmd.actions.lastIndex) { panel = it }
                        }
                    }
                } else {
                    cmd.items(section).forEachIndexed { index, item ->
                        key(item.id) {
                            val itemMeta = item.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it) }
                            Column(Modifier.fillMaxWidth().testTag("item-${item.id}").animatePlacement().staggerIn(index)
                                .clip(RoundedRectangle(24.dp)).background(C.Tonal)
                                .editorAction("编辑${item.name}") {
                                    if (section == CommandSection.ACTIONS) {
                                        state.actionEditingCmd = cmd.id
                                        state.actionEditing = item.id
                                    } else {
                                        panel = EditorPanel.Parameter(section, item.id)
                                    }
                                }
                                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LineIcon(item.ic, C.Primary, 22.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        T(item.name, 15.sp, FontWeight.SemiBold)
                                        T(itemParameterLabel(section, item), 13.sp, color = C.OnSurfaceVar)
                                    }
                                }
                                if (section != CommandSection.ACTIONS && itemMeta?.comingSoon == true) {
                                    T("即将支持，暂不会自动运行", 11.sp, color = C.Mid)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TaskAuthorizationBadge(state, section, item) { destination ->
                                        panel = null
                                        onAuthorization(destination)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (index > 0) EditorIconButton(Ic.ArrowUp, "上移第${index + 1}项") {
                                        state.moveItem(cmd.id, section, item.id, -1)
                                    }
                                    if (index < cmd.items(section).lastIndex) EditorIconButton(Ic.ArrowDown, "下移第${index + 1}项") {
                                        state.moveItem(cmd.id, section, item.id, 1)
                                    }
                                    EditorIconButton(Ic.Trash, "删除第${index + 1}项") { state.removeItem(cmd.id, section, item.id) }
                                }
                            }
                        }
                    }
                }
                if (section == CommandSection.TRIGGERS) {
                    T("多个触发器按「任一满足」执行；正在执行时新的触发会被丢弃。",
                        11.sp, color = C.Muted, lineHeight = 16.sp)
                }
                if (section == CommandSection.CONDITIONS) {
                    T("多个条件需要全部满足才会执行。", 11.sp, color = C.Muted, lineHeight = 16.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(Capsule()).background(C.Card)
            .editorAction("删除指令") { state.delete(cmd.id) }.padding(16.dp), contentAlignment = Alignment.Center) {
            T("删除指令", 15.sp, FontWeight.SemiBold, C.Bad)
        }
        Spacer(Modifier.height(16.dp))
    }
    panel?.let { active ->
        EditorGlassPanel(backdrop, when (active) { is EditorPanel.Add -> "添加${active.section.label}"; is EditorPanel.Parameter -> "编辑参数"; EditorPanel.Icons -> "选择图标"; EditorPanel.Variables -> "变量" },
            bottom = active is EditorPanel.Add, onClose = { panel = null }) {
            when (active) {
                EditorPanel.Icons -> CommandIcons.chunked(4).forEach { icons ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        icons.forEach { icon ->
                            Box(Modifier.weight(1f).height(64.dp).clip(PolygonShape(M3EShapes.Clover4))
                                .background(if (icon == cmd.ic) C.PrimarySoft else C.Card)
                                .semantics { selected = icon == cmd.ic }
                                .editorAction("图标：${icon.editorLabel}") { state.setIcon(cmd.id, icon); panel = null },
                                contentAlignment = Alignment.Center) { LineIcon(icon, C.Primary, 26.dp) }
                        }
                        repeat(4 - icons.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                is EditorPanel.Add -> {
                    val activeTier = if (state.root?.granted == true) com.flowglass.engine.Tier.ROOT
                        else com.flowglass.engine.Tier.NORMAL
                    ComponentPicker(active.section, activeTier) { option ->
                        when {
                            active.parentId == null -> state.addItem(cmd.id, active.section, option)
                            active.section == CommandSection.CONDITIONS ->
                                state.addConditionItem(cmd.id, active.parentId, active.branch, option)
                            else -> state.addChildItem(cmd.id, active.parentId, active.branch, option)
                        }
                        panel = null
                    }
                }
                is EditorPanel.Parameter -> state.itemById(cmd.id, active.itemId)?.let { item ->
                    ActionParameterEditor(active.section, item,
                        onParameter = { state.setItemParameter(cmd.id, active.section, item.id, it) },
                        onDone = { panel = null }, commandTitle = cmd.title,
                        targets = (state.autoCmds + state.oneTaps).map { it.title },
                        onAuthorization = { destination -> panel = null; onAuthorization(destination) })
                }
                EditorPanel.Variables -> VariablePanel(state, cmd)
            }
        }
    }
    }
}

internal sealed interface EditorPanel {
    data object Icons : EditorPanel
    data class Add(val section: CommandSection, val parentId: Long? = null, val branch: Int? = null) : EditorPanel
    data class Parameter(val section: CommandSection, val itemId: Long) : EditorPanel
    data object Variables : EditorPanel
}

@Composable
internal fun StorageErrorNotice(error: String?) {
    if (error != null) T(error, 14.sp, color = C.Bad,
        modifier = Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp))
}

internal val CommandSection.label: String
    get() = when (this) {
        CommandSection.TRIGGERS -> "触发器"
        CommandSection.CONDITIONS -> "条件"
        CommandSection.ACTIONS -> "任务"
    }

// Deliberate whitelist: UI affordances must not become new command identities.
private val CommandIcons = listOf(
    Ic.Home, Ic.Bolt, Ic.Play, Ic.Bell, Ic.Shield, Ic.Wifi, Ic.Moon, Ic.Battery,
    Ic.Timer, Ic.Location, Ic.Leaf, Ic.Gauge, Ic.Rocket, Ic.Clock, Ic.Phone,
    Ic.Bluetooth, Ic.Message, Ic.Camera, Ic.Tap, Ic.Lock, Ic.Terminal, Ic.Puzzle, Ic.Key,
)

private val Ic.editorLabel: String
    get() = when (this) {
        Ic.Home -> "主页"; Ic.Grid -> "网格"; Ic.List -> "列表"; Ic.Settings -> "设置"
        Ic.Bolt -> "闪电"; Ic.Copy -> "复制"; Ic.ChevronRight -> "向右"; Ic.UpDown -> "排序"
        Ic.ArrowUp -> "上箭头"; Ic.ArrowDown -> "下箭头"; Ic.Play -> "播放"; Ic.Stop -> "停止"
        Ic.Plus -> "添加"; Ic.Bell -> "通知"; Ic.Refresh -> "刷新"; Ic.Shield -> "盾牌"
        Ic.Wifi -> "无线网络"; Ic.Moon -> "月亮"; Ic.Battery -> "电池"; Ic.Timer -> "计时器"
        Ic.Location -> "位置"; Ic.Leaf -> "叶子"; Ic.Gauge -> "仪表"; Ic.Rocket -> "火箭"
        Ic.Clock -> "时钟"; Ic.Phone -> "手机"; Ic.Bluetooth -> "蓝牙"; Ic.Message -> "消息"
        Ic.Camera -> "相机"; Ic.Info -> "信息"; Ic.Search -> "搜索"; Ic.Accessibility -> "无障碍"
        Ic.Tap -> "轻点"; Ic.Back -> "返回"; Ic.Lock -> "锁"; Ic.Terminal -> "终端"
        Ic.Puzzle -> "拼图"; Ic.Key -> "钥匙"; Ic.Pin -> "置顶"; Ic.Edit -> "编辑"
        Ic.Share -> "分享"; Ic.Trash -> "删除"
    }

/** Original press spring, with equivalent keyboard/accessibility activation. */
internal fun Modifier.editorAction(label: String, action: () -> Unit): Modifier =
    semantics { role = Role.Button; contentDescription = label; onClick { action(); true } }
        .pressable(onClick = action)

@Composable
private fun NoticeCard(text: String, color: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color, onDismiss: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp)).background(background).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        T(text, 12.sp, color = color, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        if (onDismiss != null) EditorIconButton(Ic.Trash, "关闭提示") { onDismiss() }
    }
}

@Composable
private fun VariablePanel(state: AppState, cmd: Cmd) {
    val locals = state.localsOf(cmd.id)
    T("指令变量", 15.sp, FontWeight.SemiBold)
    T("只在这条指令里有效，运行结束后写回初始值。", 12.sp, color = C.Muted)
    locals.forEach { variable ->
        VariableRow(
            name = variable.name,
            value = variable.initial.toString().trim('"'),
            onName = {},
            onValue = { state.setLocal(cmd.id, variable.name, it, variable.type) },
            onDelete = { state.removeLocal(cmd.id, variable.name) },
        )
    }
    EditorPill("＋指令变量", "添加指令变量") {
        state.setLocal(cmd.id, "var${locals.size + 1}", "")
    }
    Spacer(Modifier.height(6.dp))
    T("全局变量", 15.sp, FontWeight.SemiBold)
    T("所有指令共用；运行成功或失败都会写回，手动停止不写。", 12.sp, color = C.Muted)
    state.engineGlobals.toSortedMap().forEach { (name, json) ->
        VariableRow(
            name = name,
            value = json.toString().trim('"'),
            onName = {},
            onValue = { state.setGlobal(name, it) },
            onDelete = { state.removeGlobal(name) },
        )
    }
    EditorPill("＋全局变量", "添加全局变量") { state.setGlobal("g${state.engineGlobals.size + 1}", "") }
    Spacer(Modifier.height(6.dp))
    T("在参数里用 %{rule.变量名} 或 %{global.变量名} 引用；运行期临时变量用 %{run.变量名}。",
        12.sp, color = C.Muted, lineHeight = 17.sp)
}

@Composable
private fun VariableRow(name: String, value: String, onName: (String) -> Unit, onValue: (String) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T(name, 13.sp, color = C.OnSurfaceVar, modifier = Modifier.weight(0.4f))
        BasicTextField(value, onValue, Modifier.weight(0.6f).clip(RoundedRectangle(12.dp)).background(C.Card).padding(8.dp),
            textStyle = TextStyle(color = C.OnSurface, fontSize = 13.sp), singleLine = true,
            cursorBrush = SolidColor(C.Primary),
            decorationBox = { inner -> Box { if (value.isEmpty()) T("值", 13.sp, color = C.Muted); inner() } })
        EditorIconButton(Ic.Trash, "删除变量$name") { onDelete() }
    }
}

/** The "..." menu on every step: order changes plus in/out of the enclosing block. */
@Composable
private fun ActionRowMenu(
    state: AppState,
    cmd: Cmd,
    item: CommandItem,
    depth: Int,
    index: Int,
    lastIndex: Int,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        EditorIconButton(Ic.UpDown, "更多操作${item.name}") { open = true }
        if (open) {
            Popup(onDismissRequest = { open = false }, offset = IntOffset(-220, 0)) {
                Column(
                    Modifier.clip(RoundedRectangle(18.dp)).background(C.Card).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MenuEntry("上移", enabled = index > 0) {
                        open = false
                        state.moveItem(cmd.id, CommandSection.ACTIONS, item.id, -1)
                    }
                    MenuEntry("下移", enabled = index < lastIndex) {
                        open = false
                        state.moveItem(cmd.id, CommandSection.ACTIONS, item.id, 1)
                    }
                    MenuEntry("移入上一个块", enabled = state.canMoveIntoPreviousBlock(cmd.id, item.id)) {
                        open = false
                        state.moveIntoPreviousBlock(cmd.id, item.id)
                    }
                    MenuEntry("移出当前块", enabled = depth > 0) {
                        open = false
                        state.moveOutOfBlock(cmd.id, item.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(label: String, enabled: Boolean, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedRectangle(12.dp))
            .clickable(enabled = enabled, onClick = action)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, 14.sp, color = if (enabled) C.OnSurface else C.Muted)
    }
}

@Composable
internal fun EditorIconButton(ic: Ic, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.size(48.dp).clip(Capsule()).background(C.Card).editorAction(label, action),
        contentAlignment = Alignment.Center) { LineIcon(ic, C.OnSurface, 22.dp) }
}

/**
 * One task step and everything nested in it.
 *
 * C1 keeps this deliberately plain: indent + move/delete + "add step/condition" buttons. C2 adds
 * folding, drag and the validation banner on top of the same tree.
 */
@Composable
private fun ActionTreeRow(
    state: AppState,
    cmd: Cmd,
    item: CommandItem,
    depth: Int,
    index: Int,
    lastIndex: Int,
    onPanel: (EditorPanel) -> Unit,
) {
    val meta = item.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it) }
    val block = meta?.block
    val container = block == com.flowglass.engine.BlockKind.IF ||
        block == com.flowglass.engine.BlockKind.LOOP ||
        block == com.flowglass.engine.BlockKind.WAIT
    val nested = item.conditions.size + item.children.size +
        item.branches.sumOf { it.children.size + it.conditions.size }
    val open = !item.collapsed
    val indent = (depth * 18).dp
    val density = LocalDensity.current
    var dragY by remember { mutableFloatStateOf(0f) }
    val dragStep = with(density) { 64.dp.toPx() }
    val hint = state.validationIssues(cmd).firstOrNull { it.contains("「${item.name}」") }
    Column(Modifier.fillMaxWidth().padding(start = indent), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Tonal).padding(12.dp)
                // Tap opens the parameter page; the drag detector only starts after a 300 ms
                // hold, and a scroll gesture cancels the tap because the list consumes it.
                .pointerInput(item.id) {
                    detectTapGestures(onTap = {
                        state.actionEditingCmd = cmd.id
                        state.actionEditing = item.id
                    })
                }
                .pointerInput(item.id) {
                    var dy = 0f
                    detectDragGesturesAfterLongPress(
                        onDragEnd = {
                            val raw = (dy / dragStep).roundToInt()
                            dy = 0f
                            if (raw in 1..nested && nested > 0) {
                                state.editorNotice = "不能把「${item.name}」拖进它自己的子步骤"
                            } else {
                                val steps = if (raw > 0) raw - nested else raw
                                if (steps != 0) state.moveItem(cmd.id, CommandSection.ACTIONS, item.id, steps)
                            }
                        },
                        onDragCancel = { dy = 0f },
                        onDrag = { change, amount -> change.consume(); dy += amount.y },
                    )
                }
                .semantics {
                    contentDescription = "编辑${item.name}"
                    onClick(label = "编辑") {
                        state.actionEditingCmd = cmd.id
                        state.actionEditing = item.id
                        true
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (container && nested > 0) {
                Box(Modifier.size(40.dp).clip(Capsule()).background(C.Card)
                    .editorAction(if (open) "折叠${item.name}" else "展开${item.name}") {
                        state.toggleCollapsed(cmd.id, item.id)
                    }, contentAlignment = Alignment.Center) {
                    LineIcon(Ic.ChevronRight, C.OnSurface, 20.dp,
                        modifier = Modifier.rotate(if (open) 90f else 0f))
                }
                Spacer(Modifier.width(6.dp))
            }
            LineIcon(item.ic, C.Primary, 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(item.name, 15.sp, FontWeight.SemiBold)
                val summary = when {
                    !open -> "已折叠 · $nested 项"
                    block == com.flowglass.engine.BlockKind.IF ->
                        "条件 ${item.conditions.size} · 分支 ${item.branches.size} · 步骤 ${item.children.size}"
                    block == com.flowglass.engine.BlockKind.LOOP ->
                        "循环 ${item.children.size} 步" + if (item.conditions.isNotEmpty()) " · 条件 ${item.conditions.size}" else ""
                    block == com.flowglass.engine.BlockKind.WAIT ->
                        "等待条件 ${item.conditions.size}"
                    else -> item.parameter
                }
                T(summary, 13.sp, color = C.OnSurfaceVar)
                hint?.let { T(it, 11.sp, color = C.Bad) }
            }
            ActionRowMenu(state, cmd, item, depth, index, lastIndex)
            EditorIconButton(Ic.Trash, "删除${item.name}") { state.removeItem(cmd.id, CommandSection.ACTIONS, item.id) }
        }
        if (open) {
            item.conditions.forEachIndexed { conditionIndex, condition ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp).clip(RoundedRectangle(20.dp))
                    .background(C.Card).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    T("条件", 11.sp, color = C.Muted)
                    Spacer(Modifier.width(8.dp))
                    T(if (condition.parameter.isEmpty()) condition.name else "${condition.name}：${condition.parameter}",
                        13.sp, color = C.OnSurfaceVar, modifier = Modifier.weight(1f))
                    EditorIconButton(Ic.Trash, "删除条件${conditionIndex + 1}") {
                        state.removeItem(cmd.id, CommandSection.CONDITIONS, condition.id)
                    }
                }
            }
            if (item.children.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 18.dp).drawBehind {
                        drawLine(C.Divider, androidx.compose.ui.geometry.Offset(1.dp.toPx(), 0f),
                            androidx.compose.ui.geometry.Offset(1.dp.toPx(), size.height), 2.dp.toPx())
                    }.padding(start = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.children.forEachIndexed { childIndex, child ->
                        key(child.id) {
                            ActionTreeRow(state, cmd, child, depth + 1, childIndex, item.children.lastIndex, onPanel)
                        }
                    }
                }
            }
            item.branches.forEachIndexed { branchIndex, branch ->
                Column(Modifier.fillMaxWidth().padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(if (branch.conditions.isEmpty()) "否则" else "否则如果（${branch.conditions.size} 个条件）",
                            12.sp, color = C.Mid, modifier = Modifier.weight(1f))
                        EditorIconButton(Ic.Trash, "删除分支${branchIndex + 1}") {
                            state.removeBranch(cmd.id, item.id, branchIndex)
                        }
                    }
                    branch.conditions.forEach { condition ->
                        T("· ${condition.name}${if (condition.parameter.isEmpty()) "" else "：${condition.parameter}"}",
                            12.sp, color = C.OnSurfaceVar)
                    }
                    branch.children.forEachIndexed { childIndex, child ->
                        key(child.id) {
                            ActionTreeRow(state, cmd, child, depth + 1, childIndex, branch.children.lastIndex, onPanel)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorPill("＋步骤", "向该分支添加步骤") {
                            onPanel(EditorPanel.Add(CommandSection.ACTIONS, item.id, branchIndex))
                        }
                        if (branch.conditions.isNotEmpty()) EditorPill("＋条件", "向该分支添加条件") {
                            onPanel(EditorPanel.Add(CommandSection.CONDITIONS, item.id, branchIndex))
                        }
                    }
                }
            }
            when (block) {
                com.flowglass.engine.BlockKind.IF, com.flowglass.engine.BlockKind.LOOP, com.flowglass.engine.BlockKind.WAIT -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (block != com.flowglass.engine.BlockKind.WAIT) EditorPill("＋步骤", "向${item.name}添加步骤") {
                            onPanel(EditorPanel.Add(CommandSection.ACTIONS, item.id))
                        }
                        EditorPill("＋条件", "向${item.name}添加条件") {
                            onPanel(EditorPanel.Add(CommandSection.CONDITIONS, item.id))
                        }
                        if (block == com.flowglass.engine.BlockKind.IF) {
                            EditorPill("＋否则如果", "添加否则如果") { state.addBranch(cmd.id, item.id, withCondition = true) }
                            EditorPill("＋否则", "添加否则") { state.addBranch(cmd.id, item.id, withCondition = false) }
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

