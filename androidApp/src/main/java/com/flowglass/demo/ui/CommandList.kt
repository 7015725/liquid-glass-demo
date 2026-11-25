package com.flowglass.demo.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.demo.system.trigger.listenState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ================================================================ pages

@Composable
fun AutoCommandsScreen(state: AppState) {
    // 全部 / 已开启 + every trigger category the component registry declares.
    val filters = remember { autoFilters() }
    val f = state.autoFilter.coerceIn(0, filters.lastIndex)
    val all = state.sorted(auto = true)
    val list = all.filter(state::matches).filter { cmd ->
        when (f) {
            0 -> true
            1 -> cmd.enabled
            else -> cmd.categoryName() == filters[f]
        }
    }
    val enabled = state.autoCmds.count { it.enabled }

    ListNotice(state)
    FilterChips(filters, f, { state.autoFilter = it }, Modifier.staggerIn(0))
    ListHeader(
        "已启用 $enabled / ${state.autoCmds.size} 条",
        Modifier.staggerIn(1)
    )
    if (!state.running) EnginePausedBanner(state, Modifier.staggerIn(2))
    CommandColumn(list, state, auto = true, groupKey = f)
    if (list.isEmpty()) {
        when {
            !state.query.isNullOrBlank() -> EmptyState(
                Ic.Search, "没有匹配「${state.query}」的指令",
                "搜索会匹配标题、描述和触发器名称。",
                actionLabel = "清空搜索", actionTag = "auto-search-empty-clear",
            ) { state.query = null }
            all.isEmpty() -> EmptyState(
                Ic.Bolt, "还没有自动指令",
                "按时间、电量、充电或亮屏等条件自动运行一组任务。",
                actionLabel = "新建自动指令", actionTag = "auto-empty-create",
            ) { state.openEditor(state.create(auto = true).id) }
            else -> EmptyState(
                Ic.Search, "「${filters[f]}」下没有指令",
                "这条指令的触发器不属于这个分类，可以切回「全部」查看。",
                actionLabel = "查看全部", actionTag = "auto-category-empty-all",
            ) { state.autoFilter = 0 }
        }
    }
}

@Composable
fun OneTapCommandsScreen(state: AppState) {
    val list = state.sorted(auto = false).filter(state::matches)
    ListNotice(state)
    ListHeader(
        "共 ${list.size} 条 · 置顶 ${list.count { it.pinned }}",
        Modifier.staggerIn(0)
    )
    CommandColumn(list, state, auto = false, groupKey = 0)
    if (list.isEmpty()) {
        if (!state.query.isNullOrBlank()) {
            EmptyState(
                Ic.Search, "没有匹配「${state.query}」的指令",
                "搜索会匹配标题、描述和任务名称。",
                actionLabel = "清空搜索", actionTag = "onetap-search-empty-clear",
            ) { state.query = null }
        } else {
            EmptyState(
                Ic.Tap, "还没有一键指令",
                "把常用的几个动作收进一条指令，然后在桌面点一下就能执行。",
                actionLabel = "新建一键指令", actionTag = "onetap-empty-create",
            ) { state.openEditor(state.create(auto = false).id) }
        }
    }
}

@Composable
private fun ListHeader(text: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text, 14.sp, FontWeight.Medium, C.OnSurfaceVar, Modifier.weight(1f))
    }
}

@Composable
private fun EnginePausedBanner(state: AppState, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedRectangle(22.dp)).background(C.Tonal)
            .pressable(0.98f) { state.setEngineRunning(true) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(Ic.Info, C.OnSurfaceVar, 18.dp)
        Spacer(Modifier.width(10.dp))
        T("引擎已暂停，自动指令不会触发", 13.sp, color = C.OnSurfaceVar, modifier = Modifier.weight(1f))
        T("恢复", 13.sp, FontWeight.Bold, C.Primary)
    }
}

@Composable
private fun CommandColumn(list: List<Cmd>, state: AppState, auto: Boolean, groupKey: Int) {
    key(groupKey) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            list.forEachIndexed { i, cmd ->
                key(cmd.id) {
                    CommandCard(
                        cmd, state,
                        Modifier.animatePlacement().staggerIn((i + 2).coerceAtMost(12))
                    )
                }
            }
        }
    }
}

// ================================================================ card

@Composable
fun CommandCard(cmd: Cmd, state: AppState, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val exec = state.executing[cmd.id] ?: 0
    val context = LocalContext.current
    val tick = com.flowglass.demo.system.SysTick.tick
    val listen = cmd.listenState(state.running, context)
    val cardBackdrop = rememberLayerBackdrop()
    val dim by animateFloatAsState(
        if (cmd.auto && !cmd.enabled) 0.55f else 1f, effect(), label = "dim"
    )
    val textDim by animateFloatAsState(
        if (cmd.auto && !state.running) 0.8f else 1f, effect(), label = "textDim"
    )
    val menuOpen = state.menu?.cmdId == cmd.id
    val lift by animateFloatAsState(if (menuOpen) 1f else 0f, spatial(), label = "lift")

    PressCard(
        modifier.fillMaxWidth().graphicsLayer {
            val s = 1f + 0.02f * lift
            scaleX = s; scaleY = s
        },
        onClick = { state.openEditor(cmd.id) },
        radius = 26.dp,
        color = Color.Transparent,
        contentPadding = 16.dp,
        onLongPress = { pos ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            state.menu = MenuReq(cmd.id, cmd.auto, pos)
        },
        background = {
            Box(Modifier.matchParentSize().layerBackdrop(cardBackdrop).background(C.Card))
        },
        foreground = Modifier.shimmer(exec == 1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).graphicsLayer { alpha = dim }
                    .clip(PolygonShape(M3EShapes.Clover4))
                    .background(if (cmd.auto && cmd.enabled) C.PrimarySoft else C.Tonal),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(cmd.ic, if (cmd.auto && cmd.enabled) C.Primary else C.OnSurfaceVar, 22.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).graphicsLayer { alpha = textDim }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cmd.pinned) {
                        LineIcon(Ic.Pin, C.Primary, 14.dp, filled = true)
                        Spacer(Modifier.width(4.dp))
                    }
                    T(cmd.title, 16.sp, FontWeight.Bold, maxLines = 1)
                }
                if (cmd.desc.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    T(cmd.desc, 13.sp, color = C.OnSurfaceVar, maxLines = 2, lineHeight = 18.sp)
                    Spacer(Modifier.height(6.dp))
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(cmd.meta, 11.sp, FontWeight.Medium, C.Muted)
                    if (!cmd.auto) {
                        T("  ·  上次 ${cmd.lastRun}", 11.sp, color = C.Muted)
                    }
                }
                val blocked = if (cmd.auto) state.validationIssues(cmd) else emptyList()
                if (blocked.isNotEmpty() && !cmd.enabled) {
                    Spacer(Modifier.height(4.dp))
                    T("未通过校验：" + blocked.first(), 11.sp, color = C.Bad, maxLines = 2)
                }
                if (cmd.auto) {
                    Spacer(Modifier.height(6.dp))
                    ListenStatePill(listen)
                }
            }
            Spacer(Modifier.width(10.dp))
            if (cmd.auto) {
                val blocked = state.validationIssues(cmd)
                Box(Modifier.graphicsLayer { alpha = if (blocked.isNotEmpty() && !cmd.enabled) 0.4f else 1f }) {
                LiquidToggle({ cmd.enabled }, {
                    if (!cmd.enabled && blocked.isNotEmpty()) state.editorNotice = blocked.first()
                    else PermissionGate.request(state, cmd, context)
                }, cardBackdrop,
                    Modifier.testTag("command-toggle-${cmd.id}")
                        // The liquid thumb observes gestures without consuming them. Consume on
                        // the parent's Main pass, after the thumb but before the card recognizer.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false).consume()
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }.semantics(mergeDescendants = true) {
                        role = Role.Switch
                        contentDescription = "启用${cmd.title}"
                        toggleableState = if (cmd.enabled) ToggleableState.On else ToggleableState.Off
                        onClick {
                            if (!cmd.enabled && blocked.isNotEmpty()) state.editorNotice = blocked.first()
                            else PermissionGate.request(state, cmd, context)
                            true
                        }
                    })
                }
            } else {
                ExecButton(exec, Modifier.testTag("command-execute-${cmd.id}"),
                    failureLabel = if (state.executionMessages[cmd.id] == "执行环境未就绪") "执行环境未就绪" else "失败",
                    onClick = { state.execute(cmd, scope) },
                    onStop = if (exec == 1) ({ state.stopExecution(cmd.id) }) else null)
            }
        }
    }
}

@Composable
fun ListenStatePill(state: com.flowglass.demo.system.trigger.RuleListenState) {
    val (bg, fg) = when (state) {
        com.flowglass.demo.system.trigger.RuleListenState.LISTENING -> C.GoodSoft to C.GoodText
        com.flowglass.demo.system.trigger.RuleListenState.MISSING_PERMISSION -> C.WarnSoft to C.WarnText
        else -> C.Tonal to C.OnSurfaceVar
    }
    Box(
        Modifier.clip(Capsule()).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) { T(state.label, 11.sp, FontWeight.Medium, fg) }
}

/** Shared execution action: compact card pill or prominent editor button. */
@Composable
internal fun ExecButton(exec: Int, modifier: Modifier = Modifier, large: Boolean = false, idleLabel: String = "一键执行", failureLabel: String = "失败", onClick: () -> Unit, onStop: (() -> Unit)? = null) {
    val bg by animateColorAsState(when (exec) { 2 -> C.GoodSoft; 3 -> C.Bad; else -> C.Primary }, effect(), label = "execBg")
    Box(
        modifier.height(if (large) 56.dp else 34.dp).clip(Capsule()).background(bg)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                when {
                    exec == 0 -> { contentDescription = idleLabel; onClick { onClick(); true } }
                    exec == 1 && onStop != null -> { contentDescription = "停止"; onClick { onStop(); true } }
                    else -> { contentDescription = idleLabel; disabled() }
                }
            }
            .pressable(0.9f) {
                when {
                    exec == 0 -> onClick()
                    exec == 1 && onStop != null -> onStop()
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            exec,
            transitionSpec = { (fadeIn(effect()) + scaleIn(spatial(), 0.8f)) togetherWith (fadeOut(effect()) + scaleOut(spatial(), 0.8f)) },
            label = "exec"
        ) { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (s) {
                    1 -> {
                        if (onStop != null) {
                            LineIcon(Ic.Stop, C.OnPrimary, if (large) 20.dp else 14.dp)
                            Spacer(Modifier.width(6.dp))
                            T("停止", if (large) 16.sp else 12.sp, FontWeight.SemiBold, C.OnPrimary)
                        } else {
                            Spinner(C.OnPrimary, if (large) 20.dp else 14.dp)
                            Spacer(Modifier.width(6.dp))
                            T("执行中", if (large) 16.sp else 12.sp, FontWeight.SemiBold, C.OnPrimary)
                        }
                    }
                    2 -> {
                        CheckBadge(true, if (large) 22.dp else 16.dp)
                        Spacer(Modifier.width(6.dp))
                        T("已完成", if (large) 16.sp else 12.sp, FontWeight.SemiBold, C.GoodText)
                    }
                    3 -> {
                        LineIcon(Ic.Info, C.OnBad, if (large) 20.dp else 14.dp)
                        Spacer(Modifier.width(6.dp))
                        T(failureLabel, if (large) 16.sp else 12.sp, FontWeight.SemiBold, C.OnBad)
                    }
                    else -> {
                        LineIcon(Ic.Play, C.OnPrimary, if (large) 20.dp else 14.dp,
                            modifier = if (large) Modifier.testTag("editor-execute-play") else Modifier)
                        Spacer(Modifier.width(4.dp))
                        T(idleLabel, if (large) 16.sp else 12.sp, FontWeight.SemiBold, C.OnPrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun Spinner(color: Color, size: androidx.compose.ui.unit.Dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "spinA")
    val sweep by t.animateFloat(60f, 250f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "sweep")
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension * 0.16f
        drawArc(color, a, sweep, false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

// ================================================================ overlays (drawn in the app root, above content)

private data class MenuAction(val ic: Ic, val label: String, val danger: Boolean = false)

/** Shared dropdown anchored to the long-press point, with the same surface as every other menu. */
@Composable
fun CommandMenuOverlay(state: AppState) {
    val req = state.menu
    val progress = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<MenuReq?>(null) }
    LaunchedEffect(req) {
        if (req != null) {
            shown = req
            progress.snapTo(0f)
            progress.animateTo(1f, spatial())
        } else if (shown != null) {
            progress.animateTo(0f, effect())
            shown = null
        }
    }
    val r = shown ?: return
    val cmd = state.find(r.cmdId)
    if (cmd == null) {
        LaunchedEffect(Unit) { state.menu = null }
        return
    }
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val shareCmdId = state.sharingCmdId
    val actions = listOf(
        MenuAction(Ic.Pin, if (cmd.pinned) "取消置顶" else "置顶"),
        MenuAction(Ic.Edit, "重命名"),
        MenuAction(Ic.Share, "分享"),
        MenuAction(Ic.Copy, "创建副本"),
        MenuAction(Ic.Trash, "删除", danger = true),
    )
    val items = actions.map { a -> MenuItemSpec(
        a.label, a.ic, danger = a.danger, dismissOnClick = a.label != "分享",
    ) {
        if (a.label == "分享") {
            // Keep the overlay composed until the share dialog closes; otherwise
            // `shown` becomes null and this dialog's state is discarded with it.
            state.sharingCmdId = cmd.id
        } else {
            state.menu = null
            when (a.label) {
                "置顶", "取消置顶" -> state.togglePin(cmd.id)
                "重命名" -> state.renaming = cmd
                "创建副本" -> state.duplicate(cmd.id)
                "删除" -> state.delete(cmd.id)
            }
        }
    } }
    val itemH = 50.dp
    val menuW = 208.dp
    val menuH = itemH * actions.size + 16.dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val mw = with(density) { menuW.toPx() }
        val mh = with(density) { menuH.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val maxX = (w - mw - margin).coerceAtLeast(margin)
        val x = (r.anchor.x - mw * 0.3f).coerceIn(margin, maxX)
        val below = r.anchor.y + with(density) { 12.dp.toPx() }
        val openUp = below + mh > h - with(density) { 110.dp.toPx() }
        val actualMh = boundedMenuPx(mh, h - 2 * margin)
        val maxY = (h - actualMh - margin).coerceAtLeast(margin)
        val y = if (openUp) (r.anchor.y - actualMh - with(density) { 12.dp.toPx() }).coerceIn(margin, maxY) else below
        val originX = ((r.anchor.x - x) / mw).coerceIn(0f, 1f)
        val originY = if (openUp) 1f else 0f

        // scrim
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(C.ScrimSubtle)
                .pointerInput(Unit) { detectTapGestures { state.menu = null } }
        )
        MenuSurface(
            items = items,
            onDismiss = { state.menu = null },
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .graphicsLayer {
                    val p = progress.value
                    alpha = p.coerceIn(0f, 1f)
                    val s = 0.6f + 0.4f * p
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(originX, originY)
                },
            width = menuW,
            itemHeight = itemH,
            maxHeight = with(density) { actualMh.toDp() },
        )
    }
    shareCmdId?.let(state::find)?.let { command ->
        ShareCommandDialog(state, command) {
            state.sharingCmdId = null
            state.pendingShareJson = null
            state.menu = null
        }
    }
}

/** Glass rename dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenameOverlay(state: AppState, backdrop: Backdrop) {
    val target = state.renaming
    val progress by animateFloatAsState(if (target != null) 1f else 0f, spatial(), label = "renameP")
    var last by remember { mutableStateOf<Cmd?>(null) }
    if (target != null) last = target
    val cmd = last ?: return
    if (progress <= 0.001f && target == null) return

    var text by remember(cmd.id) { mutableStateOf(TextFieldValue(cmd.title, TextRange(0, cmd.title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(target) {
        if (target != null) {
            delay(120)
            runCatching { focus.requestFocus() }
        }
    }
    fun confirm() {
        if (state.rename(cmd.id, text.text.trim())) {
            state.editorNotice = null
            state.renaming = null
        }
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
                .background(C.Scrim)
                .pointerInput(Unit) { detectTapGestures { state.renaming = null } }
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    alpha = progress.coerceIn(0f, 1f)
                    val s = 0.88f + 0.12f * progress
                    scaleX = s; scaleY = s
                }
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(30.dp) },
                    effects = {
                        themedVibrancy()
                        blur(themedBlur(14.dp.toPx()))
                        lens(themedLensX(18.dp.toPx()), themedLensY(32.dp.toPx()))
                    },
                    onDrawSurface = { drawRect(C.GlassStrong) }
                )
                .padding(22.dp)
        ) {
            T("重命名", 20.sp, FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            T(if (cmd.auto) "自动指令" else "一键指令", 13.sp, color = C.Muted)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp)).background(C.Field)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = C.OnSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(C.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().imeAutoScroll().focusRequester(focus)
                )
            }
            state.editorNotice?.let { notice ->
                Spacer(Modifier.height(10.dp))
                T(notice, 12.sp, color = C.Mid)
            }
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.width(132.dp).height(46.dp).clip(Capsule()).background(C.Tonal)
                        .pressable { state.renaming = null },
                    contentAlignment = Alignment.Center
                ) { T("取消", 15.sp, FontWeight.SemiBold, C.OnSurfaceVar, maxLines = 1) }
                Box(
                    Modifier.width(132.dp).height(46.dp).clip(Capsule()).background(C.Primary)
                        .pressable { confirm() },
                    contentAlignment = Alignment.Center
                ) { T("确定", 15.sp, FontWeight.SemiBold, C.OnPrimary, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ListNotice(state: AppState) {
    val notice = state.editorNotice ?: return
    LaunchedEffect(notice) {
        delay(5_000)
        if (state.editorNotice == notice) state.editorNotice = null
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp)).background(C.MidSoft).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(notice, 12.sp, color = C.Mid, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        Box(Modifier.testTag("list-notice-dismiss").pressable { state.editorNotice = null }) {
            T("知道了", 12.sp, color = C.Mid)
        }
    }
}

/** Glass "deleted · undo" bar above the dock. */
@Composable
fun UndoBar(state: AppState, backdrop: Backdrop, modifier: Modifier) {
    val u = state.undo
    LaunchedEffect(u) {
        if (u != null) {
            delay(4000)
            if (state.undo == u) state.undo = null
        }
    }
    AnimatedVisibility(
        u != null,
        modifier = modifier,
        enter = fadeIn(effect()) + scaleIn(spatial(), 0.85f),
        exit = fadeOut(effect()) + scaleOut(spatial(), 0.9f)
    ) {
        var lastTitle by remember { mutableStateOf("") }
        var lastMessage by remember { mutableStateOf<String?>(null) }
        if (u != null) {
            lastTitle = u.label
            lastMessage = u.message
        }
        Row(
            Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        themedVibrancy()
                        blur(themedBlur(10.dp.toPx()))
                        lens(themedLensX(14.dp.toPx()), themedLensY(24.dp.toPx()))
                    },
                    onDrawSurface = { drawRect(C.InverseSurface) }
                )
                .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            T(lastMessage ?: "已删除「$lastTitle」", 14.sp, FontWeight.Medium, C.InverseOnSurface, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.clip(Capsule()).background(C.InverseOnSurface.copy(alpha = 0.16f))
                    .pressable { state.undoDelete() }.padding(horizontal = 16.dp, vertical = 9.dp)
            ) { T("撤销", 14.sp, FontWeight.Bold, C.InversePrimary) }
        }
    }
}


/** Glass search capsule that replaces the top-right actions while searching. */
@Composable
fun SearchBarOverlay(state: AppState, backdrop: Backdrop, modifier: Modifier) {
    val open = state.query != null
    val p by animateFloatAsState(if (open) 1f else 0f, spatial(), label = "searchP")
    if (p <= 0.001f && !open) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(open) {
        if (open) { delay(100); runCatching { focus.requestFocus() } }
    }
    Row(
        modifier
            .graphicsLayer {
                alpha = p.coerceIn(0f, 1f)
                scaleX = 0.85f + 0.15f * p
                transformOrigin = TransformOrigin(1f, 0.5f)
            }
            .height(52.dp)
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    themedVibrancy()
                    blur(themedBlur(8.dp.toPx()))
                    lens(themedLensX(14.dp.toPx()), themedLensY(24.dp.toPx()))
                },
                onDrawSurface = { drawRect(C.Glass) }
            )
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(Ic.Search, C.OnSurfaceVar, 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (state.query.isNullOrEmpty()) T("搜索标题、描述、触发器或任务", 15.sp, color = C.Muted, maxLines = 1)
            BasicTextField(
                value = state.query ?: "",
                onValueChange = { state.query = it },
                singleLine = true,
                textStyle = TextStyle(color = C.OnSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(C.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().imeAutoScroll().focusRequester(focus)
            )
        }
        Box(
            Modifier.size(40.dp).clip(Capsule()).background(C.Tonal.copy(alpha = 0.7f)).pressable { state.query = null },
            contentAlignment = Alignment.Center
        ) { T("✕", 15.sp, FontWeight.Bold, C.OnSurfaceVar) }
    }
}
