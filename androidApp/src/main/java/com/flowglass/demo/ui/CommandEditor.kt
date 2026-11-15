package com.flowglass.demo.ui

import android.content.Context
import android.content.Intent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.demo.system.trigger.listenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    var moreOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var titleFocused by remember { mutableStateOf(false) }
    val backdrop = rememberLayerBackdrop()
    val exec = state.executing[cmd.id] ?: 0
    fun back() { if (panel != null) panel = null else state.closeEditor() }
    BackHandler(enabled = state.editing == cmd.id) { back() }
    Box(Modifier.fillMaxSize().background(C.Bg).testTag("command-editor")
        .pointerInput(Unit) { detectTapGestures { } }) {
    Column(Modifier.fillMaxSize().layerBackdrop(backdrop)
        .then(if (panel != null) Modifier.clearAndSetSemantics { } else Modifier)
        .statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EditorIconButton(Ic.Back, "返回") { back() }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).heightIn(min = 48.dp)) {
                // BasicTextField cannot ellipsize when unfocused; show a one-line preview and
                // swap in the editable field only while it has focus.
                if (!titleFocused) {
                    T(
                        cmd.title.ifEmpty { "指令名称" }, 20.sp, FontWeight.Bold,
                        if (cmd.title.isEmpty()) C.Muted else C.OnSurface,
                        modifier = Modifier.fillMaxWidth(), maxLines = 1,
                    )
                }
                BasicTextField(
                    cmd.title, { state.setTitle(cmd.id, it) },
                    Modifier.fillMaxWidth().testTag("editor-name")
                        .onFocusChanged { titleFocused = it.isFocused }
                        .alpha(if (titleFocused) 1f else 0f),
                    textStyle = TextStyle(color = C.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    singleLine = true,
                    cursorBrush = SolidColor(C.Primary),
                    decorationBox = { inner -> Box { if (cmd.title.isEmpty() && titleFocused) T("指令名称", 20.sp, color = C.Muted, maxLines = 1); inner() } },
                )
            }
            Spacer(Modifier.width(8.dp))
            EditorRunButton(
                exec,
                Modifier.testTag("editor-execute"),
                onClick = { state.execute(cmd, scope, if (cmd.auto) "手动测试" else "手动执行") },
                onStop = if (exec == 1) ({ state.stopExecution(cmd.id) }) else null,
            )
            Spacer(Modifier.width(4.dp))
            Box {
                EditorIconButton(Ic.More, "更多") { moreOpen = true }
                if (moreOpen) {
                    Popup(onDismissRequest = { moreOpen = false }, offset = IntOffset(-210, 52)) {
                        Column(
                            Modifier.width(200.dp).clip(RoundedRectangle(18.dp)).background(C.Card).padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            MenuEntry("变量", enabled = true) {
                                moreOpen = false
                                panel = EditorPanel.Variables
                            }
                            MenuEntry("换图标", enabled = true) {
                                moreOpen = false
                                panel = EditorPanel.Icons
                            }
                            MenuEntry("创建副本", enabled = true) {
                                moreOpen = false
                                state.duplicate(cmd.id)?.let { copyId ->
                                    state.openEditor(copyId)
                                    state.editorNotice = "已创建副本"
                                }
                            }
                            MenuEntry("分享", enabled = true) {
                                moreOpen = false
                                shareCommand(context, cmd)
                            }
                            MenuEntry("删除指令", enabled = true) {
                                moreOpen = false
                                confirmDelete = true
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                cmd.desc, { state.setDescription(cmd.id, it) },
                Modifier.weight(1f).testTag("editor-description"),
                textStyle = TextStyle(color = C.OnSurfaceVar, fontSize = 13.sp, lineHeight = 18.sp),
                maxLines = 2,
                cursorBrush = SolidColor(C.Primary),
                decorationBox = { inner ->
                    Box {
                        if (cmd.desc.isEmpty()) T("添加描述", 13.sp, color = C.Muted, maxLines = 2)
                        inner()
                    }
                },
            )
            if (cmd.auto) {
                Spacer(Modifier.width(8.dp))
                ListenStatePill(cmd.listenState(state.running, context))
                Spacer(Modifier.width(8.dp))
                AutoEnableToggle(state, cmd, context, issues)
            }
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
        MissingPermissionNotice(cmd) { onAuthorization(SettingsPage.Permissions) }
        val sections = if (cmd.auto) CommandSection.entries else listOf(CommandSection.ACTIONS)
        sections.forEachIndexed { i, section ->
            Column(Modifier.fillMaxWidth().testTag("section-${section.name}")
                .staggerIn(i).clip(com.kyant.shapes.RoundedRectangle(28.dp)).background(C.Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T(section.label, 20.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    T("${if (section == CommandSection.ACTIONS) cmd.actions.allItems().size else cmd.items(section).size}", 13.sp, color = C.Muted)
                    Spacer(Modifier.width(10.dp))
                    EditorPill("添加", "添加${section.label}") { panel = EditorPanel.Add(section) }
                }
                if (cmd.items(section).isEmpty()) T("尚未添加${section.label}", 14.sp, color = C.Muted)
                if (section == CommandSection.ACTIONS) {
                    ActionList(state, cmd, cmd.actions, 0, null, null) { panel = it }
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
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TaskAuthorizationBadge(state, section, item) { destination ->
                                        panel = null
                                        onAuthorization(destination)
                                    }
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
        Spacer(Modifier.height(16.dp))
    }
    if (confirmDelete) {
        DeleteCommandDialog(
            title = cmd.title,
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                state.delete(cmd.id)
            },
        )
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
                        val addedConditionId = if (active.section == CommandSection.CONDITIONS) {
                            addConditionFromBlockMenu(state, cmd.id, active.parentId, active.branch, option)
                        } else {
                            when {
                                active.parentId == null -> state.addItem(cmd.id, active.section, option)
                                else -> state.addChildItem(cmd.id, active.parentId, active.branch, option)
                            }
                            null
                        }
                        // A newly added condition is only half configured; jump straight to its
                        // parameter page instead of making the user find the new row again.
                        panel = addedConditionId?.let { EditorPanel.Parameter(CommandSection.CONDITIONS, it) }
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
        Ic.Bolt -> "闪电"; Ic.Copy -> "复制"; Ic.ChevronRight -> "向右"; Ic.UpDown -> "排序"; Ic.More -> "更多"
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
private fun EditorRunButton(
    exec: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onStop: (() -> Unit)?,
) {
    val running = exec == 1 && onStop != null
    val label = when {
        running -> "停止"
        exec == 2 -> "完成"
        exec == 3 -> "失败"
        else -> "运行"
    }
    val bg by animateColorAsState(
        when {
            running -> C.Bad
            exec == 2 -> C.GoodSoft
            exec == 3 -> C.Bad
            else -> C.Primary
        }, effect(), label = "headerRunBg",
    )
    val fg = if (exec == 2) C.GoodText else C.OnPrimary
    Box(
        modifier.height(40.dp).clip(Capsule()).background(bg)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label = label) {
                    if (running) onStop?.invoke() else onClick()
                    true
                }
            }
            .pressable(0.9f) { if (running) onStop?.invoke() else onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineIcon(
                if (running) Ic.Stop else if (exec == 3) Ic.Info else if (exec == 2) Ic.Refresh else Ic.Play,
                fg, 16.dp,
                modifier = if (!running && exec == 0) Modifier.testTag("editor-execute-play") else Modifier,
            )
            Spacer(Modifier.width(4.dp))
            T(label, 13.sp, FontWeight.SemiBold, fg, maxLines = 1)
        }
    }
}

@Composable
private fun AutoEnableToggle(state: AppState, cmd: Cmd, context: android.content.Context, issues: List<String>) {
    val toggleBackdrop = rememberLayerBackdrop()
    Box(Modifier.clip(Capsule()).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().layerBackdrop(toggleBackdrop).background(C.Card))
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T(if (cmd.enabled) "已开启" else "已关闭", 12.sp, color = C.OnSurfaceVar)
            Spacer(Modifier.width(6.dp))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeleteCommandDialog(title: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(C.ScrimSubtle)
                .pointerInput(Unit) { detectTapGestures { onCancel() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(320.dp).clip(RoundedRectangle(24.dp)).background(C.Card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("删除指令？", 18.sp, FontWeight.Bold)
                T("「$title」删除后可在提示条撤销。", 14.sp, color = C.OnSurfaceVar, lineHeight = 20.sp)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.width(132.dp).heightIn(min = 44.dp).clip(Capsule()).background(C.Tonal)
                            .editorAction("取消删除") { onCancel() }.padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) { T("取消", 14.sp, FontWeight.SemiBold, maxLines = 1) }
                    Box(
                        Modifier.width(132.dp).heightIn(min = 44.dp).clip(Capsule()).background(C.Bad)
                            .editorAction("确认删除") { onConfirm() }.padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) { T("删除", 14.sp, FontWeight.SemiBold, C.OnBad, maxLines = 1) }
                }
            }
        }
    }
}

private fun shareCommand(context: Context, cmd: Cmd) {
    val text = buildString {
        appendLine("【流光自动化】${cmd.title}")
        appendLine(cmd.desc)
        if (cmd.auto) {
            appendLine("触发器：${cmd.triggers.joinToString("、")}")
            if (cmd.conditions.isNotEmpty()) appendLine("条件：${cmd.conditions.joinToString("、")}")
        }
        append("任务：${cmd.actions.joinToString(" → ")}")
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching {
        context.startActivity(Intent.createChooser(send, "分享指令").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ActionRowMenu(
    state: AppState,
    cmd: Cmd,
    item: CommandItem,
    depth: Int,
    index: Int,
    lastIndex: Int,
    parentId: Long?,
    branch: Int?,
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        EditorIconButton(Ic.More, "更多操作${item.name}", size = if (compact) 40.dp else 48.dp) { open = true }
        if (open) {
            Popup(onDismissRequest = { open = false }, offset = IntOffset(-360, 0)) {
                Column(
                    Modifier.width(200.dp).clip(RoundedRectangle(18.dp)).background(C.Card).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MenuEntry("上移", enabled = index > 0) {
                        open = false
                        state.moveStepTo(cmd.id, parentId, branch, item.id, index - 1)
                    }
                    MenuEntry("下移", enabled = index < lastIndex) {
                        open = false
                        state.moveStepTo(cmd.id, parentId, branch, item.id, index + 1)
                    }
                    MenuEntry("移入上一个块", enabled = state.canMoveIntoPreviousBlock(cmd.id, item.id)) {
                        open = false
                        state.moveIntoPreviousBlock(cmd.id, item.id)
                    }
                    MenuEntry("移出当前块", enabled = depth > 0) {
                        open = false
                        state.moveOutOfBlock(cmd.id, item.id)
                    }
                    MenuEntry("删除", enabled = true) {
                        open = false
                        state.removeActionItemWithUndo(cmd.id, item.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConditionRowMenu(
    state: AppState,
    cmd: Cmd,
    item: CommandItem,
    branch: Int?,
    conditionIndex: Int,
    condition: CommandItem,
    onPanel: (EditorPanel) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        EditorIconButton(Ic.More, "更多操作条件${conditionIndex + 1}") { open = true }
        if (open) {
            Popup(onDismissRequest = { open = false }, offset = IntOffset(-170, 0)) {
                Column(
                    Modifier.width(150.dp).clip(RoundedRectangle(18.dp)).background(C.Card).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MenuEntry("编辑", enabled = true) {
                        open = false
                        onPanel(EditorPanel.Parameter(CommandSection.CONDITIONS, condition.id))
                    }
                    MenuEntry("删除", enabled = true) {
                        open = false
                        state.removeConditionItem(cmd.id, item.id, branch, condition.id)
                    }
                }
            }
        }
    }
}




/** Shared by the block add menu and the component picker; returns the new condition id. */
internal fun addConditionFromBlockMenu(
    state: AppState,
    cmdId: Long,
    parentId: Long?,
    branch: Int?,
    option: CommandOption,
): Long? {
    val command = state.find(cmdId) ?: return null
    if (parentId == null) {
        state.addItem(cmdId, CommandSection.CONDITIONS, option)
        return state.find(cmdId)?.conditions?.lastOrNull()?.id
    }
    state.addConditionItem(cmdId, parentId, branch, option)
    val parent = command.actions.findItem(parentId) ?: return null
    return when {
        branch == null -> parent.conditions.lastOrNull()
        else -> parent.branches.getOrNull(branch)?.conditions?.lastOrNull()
    }?.id
}

/** Pure menu model; tested without opening a Popup window. */
internal fun blockAddMenuLabels(
    block: com.flowglass.engine.BlockKind?,
    timesLoop: Boolean,
    branches: List<CommandBranch>,
): List<String> {
    if (block == com.flowglass.engine.BlockKind.WAIT) return listOf("条件")
    return buildList {
        add("步骤")
        if (!timesLoop) add("条件")
        if (block == com.flowglass.engine.BlockKind.IF) {
            add("否则如果")
            if (branches.none { it.conditions.isEmpty() }) add("否则")
        }
    }
}

internal fun branchAddMenuLabels(hasConditions: Boolean): List<String> =
    buildList { add("步骤"); if (hasConditions) add("条件") }

/** One compact add button at the end of a block or branch. The menu is the only place where
 * a block's allowed additions are listed, so narrow screens never have to fit four pills. */
@Composable
private fun BlockAddButton(
    label: String,
    tag: String,
    entries: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val lift = with(LocalDensity.current) { (entries.size * 44 + 60).dp.toPx().toInt() }
    Box(modifier) {
        EditorPill("＋ 添加", label, Modifier.testTag(tag)) { open = true }
        if (open) {
            Popup(onDismissRequest = { open = false }, offset = IntOffset(0, -lift)) {
                Column(
                    Modifier.width(200.dp).clip(RoundedRectangle(18.dp)).background(C.Card).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    entries.forEach { (text, action) ->
                        MenuEntry(text, enabled = true) {
                            open = false
                            action()
                        }
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
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, 14.sp, color = if (enabled) C.OnSurface else C.Muted)
    }
}

@Composable
internal fun EditorIconButton(
    ic: Ic,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    action: () -> Unit,
) {
    Box(modifier.size(size).clip(Capsule()).background(C.Card).editorAction(label, action),
        contentAlignment = Alignment.Center) {
        LineIcon(ic, C.OnSurface, if (size < 48.dp) 20.dp else 22.dp)
    }
}

private const val MaxIndentLevels = 3
private val IndentStep = 10.dp

private fun childIndent(depth: Int) = if (depth < MaxIndentLevels) IndentStep else 0.dp

/**
 * Siblings of one action list. Dragging only edits a local id order; AppState is touched once on
 * release so undo records a single move and nothing is persisted on every drag delta.
 */
@Composable
private fun ActionList(
    state: AppState,
    cmd: Cmd,
    items: List<CommandItem>,
    depth: Int,
    parentId: Long?,
    branch: Int?,
    onPanel: (EditorPanel) -> Unit,
) {
    var preview by remember(items) { mutableStateOf<List<Long>?>(null) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var startIndex by remember { mutableIntStateOf(0) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val offset = remember { Animatable(0f) }
    val heights = remember { mutableMapOf<Long, Int>() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val shadow = with(density) { 18.dp.toPx() }
    val shown = preview?.let { ids -> ids.mapNotNull { id -> items.firstOrNull { it.id == id } } } ?: items

    fun movePreview(id: Long, direction: Int) {
        val order = (preview ?: items.map { it.id }).toMutableList()
        val from = order.indexOf(id)
        val to = from + direction
        if (from >= 0 && to in order.indices) {
            order[from] = order[to]
            order[to] = id
            preview = order
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEachIndexed { index, item ->
            key(item.id) {
                val isDragging = draggingId == item.id
                Box(
                    Modifier.fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = offset.value
                                scaleX = 1.02f
                                scaleY = 1.02f
                                shadowElevation = shadow
                            }
                        }
                        .then(if (isDragging) Modifier else Modifier.animatePlacement()),
                ) {
                    ActionTreeRow(
                        state = state, cmd = cmd, item = item, depth = depth,
                        index = index, lastIndex = shown.lastIndex,
                        parentId = parentId, branch = branch,
                        onMeasuredHeight = { heights[item.id] = it },
                        onDragStart = {
                            if (draggingId == null) {
                                draggingId = item.id
                                startIndex = items.indexOfFirst { it.id == item.id }
                                preview = items.map { it.id }
                                dragY = 0f
                                scope.launch { offset.snapTo(0f) }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { dy ->
                            if (draggingId == item.id) {
                                dragY += dy
                                val step = (heights[item.id]?.toFloat() ?: 72f) + gap
                                while (dragY > step / 2f && (preview?.indexOf(item.id) ?: 0) < shown.lastIndex) {
                                    movePreview(item.id, 1)
                                    dragY -= step
                                }
                                while (dragY < -step / 2f && (preview?.indexOf(item.id) ?: 0) > 0) {
                                    movePreview(item.id, -1)
                                    dragY += step
                                }
                                scope.launch { offset.snapTo(dragY) }
                            }
                        },
                        onDragEnd = {
                            if (draggingId == item.id) {
                                val target = preview?.indexOf(item.id) ?: startIndex
                                if (target != startIndex) {
                                    state.moveStepTo(cmd.id, parentId, branch, item.id, target)
                                }
                                preview = null
                                dragY = 0f
                                scope.launch {
                                    offset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                                    if (draggingId == item.id) draggingId = null
                                }
                            }
                        },
                        onDragCancel = {
                            preview = null
                            dragY = 0f
                            scope.launch {
                                offset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                                if (draggingId == item.id) draggingId = null
                            }
                        },
                        onPanel = onPanel,
                    )
                }
            }
        }
    }
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
    parentId: Long?,
    branch: Int?,
    onMeasuredHeight: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
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
    val timesLoop = block == com.flowglass.engine.BlockKind.LOOP &&
        item.parameter.substringBefore('|') in listOf("按次数", "times")
    // pointerInput keeps its first lambda; read the latest callbacks so a recomposed sibling
    // order is visible during a long drag.
    val dragStart = rememberUpdatedState(onDragStart)
    val dragMove = rememberUpdatedState(onDrag)
    val dragEnd = rememberUpdatedState(onDragEnd)
    val dragCancel = rememberUpdatedState(onDragCancel)
    val hint = state.validationIssues(cmd).firstOrNull { it.contains("「${item.name}」") }
    val compact = LocalConfiguration.current.screenWidthDp < 300
    val rowPadding = if (compact) 8.dp else 12.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().onGloballyPositioned { onMeasuredHeight(it.size.height) }
                .clip(RoundedRectangle(24.dp)).background(C.Tonal).padding(rowPadding)
                // Tap opens the parameter page; the drag detector only starts after a 300 ms
                // hold, and a scroll gesture cancels the tap because the list consumes it.
                .pointerInput(item.id) {
                    detectTapGestures(onTap = {
                        state.actionEditingCmd = cmd.id
                        state.actionEditing = item.id
                    })
                }
                .pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragStart.value() },
                        onDragEnd = { dragEnd.value() },
                        onDragCancel = { dragCancel.value() },
                        onDrag = { change, amount -> change.consume(); dragMove.value(amount.y) },
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
                Box(Modifier.size(if (compact) 32.dp else 40.dp).clip(Capsule()).background(C.Card)
                    .editorAction(if (open) "折叠${item.name}" else "展开${item.name}") {
                        state.toggleCollapsed(cmd.id, item.id)
                    }, contentAlignment = Alignment.Center) {
                    LineIcon(Ic.ChevronRight, C.OnSurface, if (compact) 18.dp else 20.dp,
                        modifier = Modifier.rotate(if (open) 90f else 0f))
                }
                Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
            }
            LineIcon(item.ic, C.Primary, if (compact) 18.dp else 22.dp)
            Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
            Column(
                Modifier.weight(1f).then(if (compact) Modifier else Modifier.widthIn(min = 120.dp))
                    .testTag("action-text-${item.id}"),
            ) {
                if (depth >= MaxIndentLevels) T("第 ${depth + 1} 级", 10.sp, color = C.Muted, maxLines = 1)
                T(item.name, 15.sp, FontWeight.SemiBold, maxLines = 1)
                val summary = ActionSummary.summarize(item, nested, open)
                T(summary, 13.sp, color = C.OnSurfaceVar, maxLines = 2)
                hint?.let { T(it, 11.sp, color = C.Bad, maxLines = 1) }
            }
            Spacer(Modifier.width(if (compact) 2.dp else 4.dp))
            ActionRowMenu(state, cmd, item, depth, index, lastIndex, parentId, branch, compact)
        }
        if (open) {
            item.conditions.forEachIndexed { conditionIndex, condition ->
                Row(Modifier.fillMaxWidth().padding(start = 10.dp).clip(RoundedRectangle(20.dp))
                    .background(C.Card)
                    .editorAction("编辑条件${condition.name}") {
                        onPanel(EditorPanel.Parameter(CommandSection.CONDITIONS, condition.id))
                    }
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    T("条件", 11.sp, color = C.Muted)
                    Spacer(Modifier.width(8.dp))
                    T(ActionSummary.conditionRowText(condition),
                        12.sp, color = C.OnSurfaceVar, maxLines = 1, modifier = Modifier.weight(1f))
                    ConditionRowMenu(state, cmd, item, branch = null, conditionIndex, condition, onPanel)
                }
            }
            if (timesLoop && item.conditions.isNotEmpty()) {
                T("按次数循环不会使用这些条件；需要条件循环请切换方式。", 11.sp, color = C.Mid,
                    modifier = Modifier.padding(start = 16.dp))
            }
            if (item.children.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().drawBehind {
                        val rails = if (depth >= MaxIndentLevels) 3 else 1
                        repeat(rails) { rail ->
                            val x = (1 + rail * 3).dp.toPx()
                            drawLine(C.Divider, androidx.compose.ui.geometry.Offset(x, 0f),
                                androidx.compose.ui.geometry.Offset(x, size.height), 2.dp.toPx())
                        }
                    }.padding(start = childIndent(depth)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionList(state, cmd, item.children, depth + 1, item.id, null, onPanel)
                }
            }
            item.branches.forEachIndexed { branchIndex, branch ->
                Column(Modifier.fillMaxWidth().padding(start = childIndent(depth)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(if (branch.conditions.isEmpty()) "否则" else "否则如果（${branch.conditions.size} 个条件）",
                            12.sp, color = C.Mid, modifier = Modifier.weight(1f))
                        EditorIconButton(Ic.Trash, "删除分支${branchIndex + 1}") {
                            state.removeBranch(cmd.id, item.id, branchIndex)
                        }
                    }
                    branch.conditions.forEachIndexed { conditionIndex, condition ->
                        Row(Modifier.fillMaxWidth().clip(RoundedRectangle(16.dp)).background(C.Card)
                            .editorAction("编辑条件${condition.name}") {
                                onPanel(EditorPanel.Parameter(CommandSection.CONDITIONS, condition.id))
                            }.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            T(ActionSummary.conditionRowText(condition), 12.sp, color = C.OnSurfaceVar,
                                maxLines = 1, modifier = Modifier.weight(1f))
                            ConditionRowMenu(state, cmd, item, branchIndex, conditionIndex, condition, onPanel)
                        }
                    }
                    ActionList(state, cmd, branch.children, depth + 1, item.id, branchIndex, onPanel)
                    BlockAddButton(
                        label = "向该分支添加内容",
                        tag = "branch-add-${item.id}-$branchIndex",
                        entries = buildList {
                            add("步骤" to {
                                onPanel(EditorPanel.Add(CommandSection.ACTIONS, item.id, branchIndex))
                            })
                            if (branch.conditions.isNotEmpty()) add("条件" to {
                                onPanel(EditorPanel.Add(CommandSection.CONDITIONS, item.id, branchIndex))
                            })
                        },
                    )
                }
            }
            when (block) {
                com.flowglass.engine.BlockKind.IF, com.flowglass.engine.BlockKind.LOOP, com.flowglass.engine.BlockKind.WAIT -> {
                    BlockAddButton(
                        label = "向${item.name}添加内容",
                        tag = "block-add-${item.id}",
                        entries = buildList {
                            if (block != com.flowglass.engine.BlockKind.WAIT) add("步骤" to {
                                onPanel(EditorPanel.Add(CommandSection.ACTIONS, item.id))
                            })
                            if (!timesLoop) add("条件" to {
                                onPanel(EditorPanel.Add(CommandSection.CONDITIONS, item.id))
                            })
                            if (block == com.flowglass.engine.BlockKind.IF) {
                                add("否则如果" to { state.addBranch(cmd.id, item.id, withCondition = true) })
                                if (item.branches.none { it.conditions.isEmpty() }) {
                                    add("否则" to { state.addBranch(cmd.id, item.id, withCondition = false) })
                                }
                            }
                        },
                    )
                }
                else -> Unit
            }
        }
    }
}

