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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ================================================================ pages

@Composable
fun AutoCommandsScreen(state: AppState) {
    val f = state.autoFilter
    val all = state.sorted(auto = true)
    val list = all.filter {
        when (f) {
            0 -> true
            1 -> it.enabled
            else -> it.category == f
        }
    }
    val enabled = state.autoCmds.count { it.enabled }

    FilterChips(AutoCategories, f, { state.autoFilter = it }, Modifier.staggerIn(0))
    ListHeader(
        "已保存 ${state.autoCmds.size} 条", "新建",
        Modifier.staggerIn(1)
    ) { state.create(auto = true) }
    T("编写版 · 指令保持停用，点卡片编辑", 13.sp, color = C.OnSurfaceVar)
    CommandColumn(list, state, auto = true, groupKey = f)
    if (list.isEmpty()) EmptyHint("这个分类下还没有指令")
}

@Composable
fun OneTapCommandsScreen(state: AppState) {
    val list = state.sorted(auto = false)
    ListHeader(
        "共 ${list.size} 条 · 置顶 ${list.count { it.pinned }}", "新建",
        Modifier.staggerIn(0)
    ) { state.create(auto = false) }
    CommandColumn(list, state, auto = false, groupKey = 0)
    if (list.isEmpty()) EmptyHint("还没有一键指令，点右上角新建")
}

@Composable
private fun ListHeader(text: String, action: String, modifier: Modifier, onAction: () -> Unit) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text, 14.sp, FontWeight.Medium, C.OnSurfaceVar, Modifier.weight(1f))
        Row(
            Modifier.clip(Capsule()).background(C.Card).pressable { onAction() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LineIcon(Ic.Plus, C.Primary, 18.dp)
            Spacer(Modifier.width(6.dp))
            T(action, 14.sp, FontWeight.SemiBold, C.Primary)
        }
    }
}

@Composable
private fun EnginePausedBanner(state: AppState, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedRectangle(22.dp)).background(C.Tonal)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(Ic.Info, C.OnSurfaceVar, 18.dp)
        Spacer(Modifier.width(10.dp))
        T("当前版本只编写和保存指令", 13.sp, color = C.OnSurfaceVar, modifier = Modifier.weight(1f))
        T("编写版", 13.sp, FontWeight.Bold, C.Primary)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        T(text, 14.sp, color = C.Muted)
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
    val exec = 0
    val cardBackdrop = rememberLayerBackdrop()
    val dim by animateFloatAsState(
        1f, effect(), label = "dim"
    )
    val menuOpen = state.menu?.cmdId == cmd.id
    val lift by animateFloatAsState(if (menuOpen) 1f else 0f, spatial(), label = "lift")

    PressCard(
        modifier.fillMaxWidth().graphicsLayer {
            val s = 1f + 0.02f * lift
            scaleX = s; scaleY = s
        },
        radius = 26.dp,
        color = Color.Transparent,
        contentPadding = 16.dp,
        onClick = { state.edit(cmd.id) },
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
            Column(Modifier.weight(1f).graphicsLayer { alpha = dim }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cmd.pinned) {
                        LineIcon(Ic.Pin, C.Primary, 14.dp, filled = true)
                        Spacer(Modifier.width(4.dp))
                    }
                    T(cmd.title, 16.sp, FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.height(3.dp))
                T(cmd.desc, 13.sp, color = C.OnSurfaceVar, maxLines = 2, lineHeight = 18.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(cmd.meta, 11.sp, FontWeight.Medium, C.Muted)
                    if (!cmd.auto) {
                        T("  ·  仅编写", 11.sp, color = C.Muted)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            if (cmd.auto) {
                LiquidToggle({ cmd.enabled }, { state.setEnabled(cmd.id, it) }, cardBackdrop)
            } else {
                AuthoringButton("编辑", "edit-${cmd.id}", primary = false) { state.edit(cmd.id) }
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

/** Liquid-glass dropdown that springs out of the long-press point. */
@Composable
fun CommandMenuOverlay(state: AppState, backdrop: Backdrop) {
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
    val actions = listOf(
        MenuAction(Ic.Pin, if (cmd.pinned) "取消置顶" else "置顶"),
        MenuAction(Ic.Edit, "编写"),
        MenuAction(Ic.Edit, "重命名"),
        MenuAction(Ic.Share, "导出 JSON"),
        MenuAction(Ic.Copy, "创建副本"),
        MenuAction(Ic.Trash, "删除", danger = true),
    )
    val itemH = 50.dp
    val menuW = 208.dp
    val menuH = itemH * actions.size + 16.dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val mw = with(density) { menuW.toPx() }
        val mh = with(density) { menuH.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val x = (r.anchor.x - mw * 0.3f).coerceIn(margin, w - mw - margin)
        val below = r.anchor.y + with(density) { 12.dp.toPx() }
        val openUp = below + mh > h - with(density) { 110.dp.toPx() }
        val y = if (openUp) (r.anchor.y - mh - with(density) { 12.dp.toPx() }).coerceAtLeast(margin) else below
        val originX = ((r.anchor.x - x) / mw).coerceIn(0f, 1f)
        val originY = if (openUp) 1f else 0f

        // scrim
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.10f))
                .pointerInput(Unit) { detectTapGestures { state.menu = null } }
        )
        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .graphicsLayer {
                    val p = progress.value
                    alpha = p.coerceIn(0f, 1f)
                    val s = 0.6f + 0.4f * p
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(originX, originY)
                }
                .width(menuW)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(26.dp) },
                    effects = {
                        vibrancy()
                        blur(10.dp.toPx())
                        lens(16.dp.toPx(), 28.dp.toPx())
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.55f)) }
                )
                .padding(vertical = 8.dp)
        ) {
            actions.forEachIndexed { i, a ->
                val fg = if (a.danger) C.Bad else C.OnSurface
                Row(
                    Modifier.fillMaxWidth().height(itemH)
                        .graphicsLayer {
                            // items cascade in
                            val p = ((progress.value - i * 0.06f) / 0.7f).coerceIn(0f, 1f)
                            alpha = p
                            translationY = (1f - p) * -8.dp.toPx()
                        }
                        .pressable(0.96f) {
                            state.menu = null
                            when (a.label) {
                                "置顶", "取消置顶" -> state.togglePin(cmd.id)
                                "编写" -> state.edit(cmd.id)
                                "重命名" -> state.renaming = cmd
                                "导出 JSON" -> state.requestExport(cmd.id)
                                "创建副本" -> state.duplicate(cmd.id)
                                "删除" -> state.delete(cmd.id)
                            }
                        }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LineIcon(a.ic, fg, 20.dp)
                    Spacer(Modifier.width(14.dp))
                    T(a.label, 15.sp, FontWeight.Medium, fg)
                }
                if (i == actions.lastIndex - 1) {
                    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(C.Divider))
                }
            }
        }
    }
}

/** Glass rename dialog. */
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
        state.rename(cmd.id, text.text.trim())
        state.renaming = null
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.18f))
                .pointerInput(Unit) { detectTapGestures { state.renaming = null } }
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .imePadding()
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
                        vibrancy()
                        blur(14.dp.toPx())
                        lens(18.dp.toPx(), 32.dp.toPx())
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.6f)) }
                )
                .padding(22.dp)
        ) {
            T("重命名", 20.sp, FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            T(if (cmd.auto) "自动指令" else "一键指令", 13.sp, color = C.Muted)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp)).background(Color.White.copy(alpha = 0.8f))
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
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(46.dp).clip(Capsule()).background(C.Tonal)
                        .pressable { state.renaming = null },
                    contentAlignment = Alignment.Center
                ) { T("取消", 15.sp, FontWeight.SemiBold, C.OnSurfaceVar) }
                Box(
                    Modifier.weight(1f).height(46.dp).clip(Capsule()).background(C.Primary)
                        .pressable { confirm() },
                    contentAlignment = Alignment.Center
                ) { T("确定", 15.sp, FontWeight.SemiBold, Color.White) }
            }
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
        if (u != null) lastTitle = u.cmd.title
        Row(
            Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(10.dp.toPx())
                        lens(14.dp.toPx(), 24.dp.toPx())
                    },
                    onDrawSurface = { drawRect(C.OnSurface.copy(alpha = 0.72f)) }
                )
                .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            T("已删除「$lastTitle」", 14.sp, FontWeight.Medium, Color.White, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.clip(Capsule()).background(Color.White.copy(alpha = 0.16f))
                    .pressable { state.undoDelete() }.padding(horizontal = 16.dp, vertical = 9.dp)
            ) { T("撤销", 14.sp, FontWeight.Bold, Color(0xFF8CC2FF)) }
        }
    }
}
