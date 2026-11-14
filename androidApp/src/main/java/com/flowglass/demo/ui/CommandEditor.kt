package com.flowglass.demo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.liquid.LiquidToggle
import kotlinx.coroutines.CoroutineScope
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
                        LiquidToggle({ cmd.enabled }, { state.setEnabled(cmd.id, it) }, toggleBackdrop,
                            Modifier.testTag("editor-toggle").semantics {
                                contentDescription = "启用指令"
                                toggleableState = if (cmd.enabled) ToggleableState.On else ToggleableState.Off
                                onClick { state.setEnabled(cmd.id, !cmd.enabled); true }
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
        val exec = state.executing[cmd.id] ?: 0
        ExecButton(exec, Modifier.fillMaxWidth().testTag("editor-execute"), large = true,
            idleLabel = if (cmd.auto) "立即运行" else "一键执行",
            failureLabel = if (state.executionMessages[cmd.id] == "执行环境未就绪") "执行环境未就绪" else "失败") {
            state.execute(cmd, scope, if (cmd.auto) "手动测试" else "手动执行")
        }
        if (cmd.auto) T("跳过触发器和条件，直接执行任务", 12.sp, color = C.Muted)
        val sections = if (cmd.auto) CommandSection.entries else listOf(CommandSection.ACTIONS)
        sections.forEachIndexed { i, section ->
            Column(Modifier.fillMaxWidth().testTag("section-${section.name}")
                .staggerIn(i).clip(com.kyant.shapes.RoundedRectangle(28.dp)).background(C.Card).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T(section.label, 20.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    T("${cmd.items(section).size}", 13.sp, color = C.Muted)
                    Spacer(Modifier.width(10.dp))
                    if (section == CommandSection.ACTIONS) {
                        EditorPill("步骤", "打开任务编辑页") {
                            state.actionEditing = null
                            state.taskEditing = cmd.id
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    EditorPill("添加", "添加${section.label}") { panel = EditorPanel.Add(section) }
                }
                if (cmd.items(section).isEmpty()) T("尚未添加${section.label}", 14.sp, color = C.Muted)
                cmd.items(section).forEachIndexed { index, item ->
                    key(item.id) {
                        Column(Modifier.fillMaxWidth().testTag("item-${item.id}").animatePlacement().staggerIn(index)
                            .clip(RoundedRectangle(24.dp)).background(C.Tonal)
                            .editorAction("编辑${item.name}") {
                                if (section == CommandSection.ACTIONS) {
                                    state.taskEditing = cmd.id
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
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(Capsule()).background(C.Card)
            .editorAction("删除指令") { state.delete(cmd.id) }.padding(16.dp), contentAlignment = Alignment.Center) {
            T("删除指令", 15.sp, FontWeight.SemiBold, C.Bad)
        }
        Spacer(Modifier.height(16.dp))
    }
    panel?.let { active ->
        EditorGlassPanel(backdrop, when (active) { is EditorPanel.Add -> "添加${active.section.label}"; is EditorPanel.Parameter -> "编辑参数"; EditorPanel.Icons -> "选择图标" },
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
                    var rowIndex = 0
                    CommandCatalog.options(active.section).groupBy { it.categoryName }.forEach { (category, options) ->
                        T(category, 13.sp, FontWeight.Bold, C.Muted, modifier = Modifier.padding(top = 8.dp, start = 6.dp))
                        options.forEach { option ->
                            val locked = option.minTier.ordinal > activeTier.ordinal
                            Row(Modifier.fillMaxWidth().staggerIn(rowIndex++.coerceAtMost(8))
                                .clip(RoundedRectangle(24.dp))
                                .then(if (locked) Modifier else Modifier.editorAction(option.name) {
                                    state.addItem(cmd.id, active.section, option)
                                    panel = null
                                })
                                .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineIcon(option.ic, if (locked) C.Muted else C.Primary, 22.dp)
                                Spacer(Modifier.width(12.dp))
                                T(option.name, 16.sp, color = if (locked) C.Muted else C.OnSurface,
                                    modifier = Modifier.weight(1f))
                                if (locked) T("需要 Root", 12.sp, color = C.Muted)
                                else LineIcon(Ic.Plus, C.Primary, 20.dp)
                            }
                        }
                    }
                }
                is EditorPanel.Parameter -> cmd.items(active.section).firstOrNull { it.id == active.itemId }?.let { item ->
                    ActionParameterEditor(active.section, item,
                        onParameter = { state.setItemParameter(cmd.id, active.section, item.id, it) },
                        onDone = { panel = null }, commandTitle = cmd.title)
                }
            }
        }
    }
    }
}

internal sealed interface EditorPanel {
    data object Icons : EditorPanel
    data class Add(val section: CommandSection) : EditorPanel
    data class Parameter(val section: CommandSection, val itemId: Long) : EditorPanel
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
internal fun EditorIconButton(ic: Ic, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.size(48.dp).clip(Capsule()).background(C.Card).editorAction(label, action),
        contentAlignment = Alignment.Center) { LineIcon(ic, C.OnSurface, 22.dp) }
}
