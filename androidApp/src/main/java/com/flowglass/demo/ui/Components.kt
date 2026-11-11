package com.flowglass.demo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.round
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------- cards

/** Large rounded card; scales to 0.97 and rounds its corners a bit more while pressed. */
@Composable
fun PressCard(
    modifier: Modifier = Modifier,
    radius: Dp = 30.dp,
    color: Color = C.Card,
    onClick: (() -> Unit)? = null,
    onLongPress: ((rootPosition: Offset) -> Unit)? = null,
    contentPadding: Dp = 20.dp,
    background: (@Composable BoxScope.() -> Unit)? = null,
    foreground: Modifier? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val p by animateFloatAsState(if (pressed) 1f else 0f, spatial(), label = "press")
    val shape = RoundedRectangle(radius + 6.dp * p)
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier
            .onGloballyPositioned { coords = it }
            .graphicsLayer {
                val s = 1f - 0.03f * p
                scaleX = s; scaleY = s
            }
            .clip(shape)
            .background(color)
            .then(if (onClick != null || onLongPress != null) Modifier.combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { click?.invoke() },
                onLongClick = if (onLongPress == null) null else ({
                    val c = coords
                    if (c != null && c.isAttached) longPress?.invoke(c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f))
                })
            ) else Modifier)
    ) {
        background?.invoke(this)
        Box(Modifier.padding(contentPadding)) { content() }
        if (foreground != null) Box(Modifier.matchParentSize().then(foreground))
    }
}

/** Animates an item from its previous position to its new one when the list reorders. */
fun Modifier.animatePlacement(): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(IntOffset.Zero) }
    var anim by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val a = anim ?: Animatable(target, IntOffset.VectorConverter).also { anim = it }
            if (a.targetValue != target) {
                scope.launch { a.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 380f)) }
            }
            a.value - target
        }
}

/** Shared press-scale behaviour for small controls. */
fun Modifier.pressable(scaleTo: Float = 0.92f, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val p by animateFloatAsState(if (pressed) 1f else 0f, spatial(), label = "pressable")
    val click by rememberUpdatedState(onClick)
    this
        .graphicsLayer {
            val s = 1f - (1f - scaleTo) * p
            scaleX = s; scaleY = s
        }
        .heightIn(min = 48.dp)
        .clickable(interactionSource = interaction, indication = null, onClick = { click() })
}

// ---------------------------------------------------------------- pills & labels

@Composable
fun StatusPill(text: String, active: Boolean) {
    val bg by animateColorAsState(if (active) C.GoodSoft else C.Tonal, effect(), label = "pillBg")
    val fg by animateColorAsState(if (active) C.GoodText else C.OnSurfaceVar, effect(), label = "pillFg")
    val breath = rememberInfiniteTransition(label = "breath")
    val a by breath.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "dot"
    )
    Row(
        Modifier.clip(Capsule()).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(7.dp)
                .graphicsLayer { alpha = if (active) a else 1f }
                .clip(Capsule()).background(fg)
        )
        Gap(Modifier.width(6.dp))
        AnimatedContent(text, transitionSpec = { fadeIn(effect()) togetherWith fadeOut(effect()) }, label = "pillText") {
            T(it, 12.sp, FontWeight.Medium, fg)
        }
    }
}

@Composable
fun TonalChip(text: String, icon: Ic? = null) {
    Row(
        Modifier.clip(Capsule()).background(C.Tonal).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            LineIcon(icon, C.OnSurfaceVar, 16.dp)
            Gap(Modifier.width(6.dp))
        }
        T(text, 13.sp, FontWeight.Medium, C.OnSurfaceVar)
    }
}

/** Digits roll vertically when they change. */
@Composable
fun RollingText(
    text: String,
    size: TextUnit,
    weight: FontWeight = FontWeight.Bold,
    color: Color = C.OnSurface,
) {
    Row {
        text.forEachIndexed { i, ch ->
            key(i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(spatial()) { if (up) it else -it } + fadeIn(effect())) togetherWith
                                (slideOutVertically(spatial()) { if (up) -it else it } + fadeOut(effect())) using
                                SizeTransform(clip = true)
                    },
                    label = "digit"
                ) { c -> T(c.toString(), size, weight, color, tabular = true) }
            }
        }
    }
}

@Composable
fun BigValue(value: String, unit: String, size: TextUnit = 40.sp, color: Color = C.OnSurface) {
    Row(verticalAlignment = Alignment.Bottom) {
        RollingText(value, size, FontWeight.Bold, color)
        Gap(Modifier.width(4.dp))
        T(unit, 13.sp, FontWeight.Normal, C.Muted, Modifier.padding(bottom = (size.value * 0.16f).dp))
    }
}

// ---------------------------------------------------------------- live data

class LiveSeries(val points: List<Float>, val shift: Float)

/** Adds a new point every second and slides the curve left smoothly. */
@Composable
fun rememberLiveSeries(base: Float, amp: Float, seed: Int, count: Int = 22): LiveSeries {
    val rnd = remember { Random(seed) }
    val pts = remember {
        mutableStateListOf<Float>().apply {
            var v = base
            repeat(count) { v = (v + rnd.nextFloat() * amp - amp / 2).coerceIn(base - amp * 2, base + amp * 2); add(v) }
        }
    }
    val shift = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            shift.snapTo(0f)
            shift.animateTo(1f, tween(1000, easing = LinearEasing))
            val last = pts.last()
            pts.add((last + rnd.nextFloat() * amp - amp / 2).coerceIn(base - amp * 2, base + amp * 2))
            pts.removeAt(0)
        }
    }
    return LiveSeries(pts, shift.value)
}

@Composable
fun Sparkline(series: LiveSeries, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val pts = series.points
        if (pts.size < 3) return@Canvas
        val min = pts.min()
        val max = pts.max()
        val range = (max - min).coerceAtLeast(0.001f)
        val step = size.width / (pts.size - 3)
        fun x(i: Int) = (i - 1 - series.shift) * step
        fun y(v: Float) = size.height * 0.15f + (1f - (v - min) / range) * size.height * 0.7f
        val line = Path()
        line.moveTo(x(0), y(pts[0]))
        for (i in 1 until pts.size) {
            val px = x(i - 1); val py = y(pts[i - 1])
            val cx = x(i); val cy = y(pts[i])
            val mx = (px + cx) / 2f
            line.cubicTo(mx, py, mx, cy, cx, cy)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(pts.size - 1), size.height)
            lineTo(x(0), size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------- organic decoration

@Composable
fun rememberTime(): Float {
    val t by produceState(0f) {
        val start = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { value = (it - start) / 1_000_000_000f }
        }
    }
    return t
}

/** A slowly drifting, softly deforming fluid blob. */
@Composable
fun Blob(color: Color, modifier: Modifier = Modifier, seed: Int = 1) {
    val time = produceState(0f) {
        val start = withFrameNanos { it }
        while (isActive) withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
    val phases = remember { val r = Random(seed); FloatArray(14) { r.nextFloat() * 6.28f } }
    Canvas(modifier) {
        val t = time.value
        val n = 7
        val cx = size.width / 2f + sin(t * 0.35f + phases[0]) * size.width * 0.06f
        val cy = size.height / 2f + sin(t * 0.28f + phases[1]) * size.height * 0.06f
        val baseR = size.minDimension * 0.42f
        val pts = List(n) { i ->
            val a = (i / n.toFloat()) * 2f * PI.toFloat() + t * 0.05f
            val r = baseR * (1f + 0.13f * sin(t * (0.5f + i * 0.07f) + phases[i + 2]))
            Offset(cx + r * kotlin.math.cos(a), cy + r * sin(a))
        }
        val path = Path()
        val mid = { a: Offset, b: Offset -> Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f) }
        val m0 = mid(pts[n - 1], pts[0])
        path.moveTo(m0.x, m0.y)
        for (i in 0 until n) {
            val p = pts[i]
            val m = mid(p, pts[(i + 1) % n])
            path.quadraticTo(p.x, p.y, m.x, m.y)
        }
        path.close()
        drawPath(path, color)
    }
}

// ---------------------------------------------------------------- the hero button

@Composable
fun ScallopButton(running: Boolean, onToggle: () -> Unit, size: Dp = 120.dp) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (running) {
            while (isActive) {
                rotation.animateTo(rotation.value + 360f, tween(20_000, easing = LinearEasing))
            }
        }
    }
    val glow = rememberInfiniteTransition(label = "glow")
    val glowA by glow.animateFloat(0.30f, 0.62f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "glowA")
    val glowS by glow.animateFloat(1.0f, 1.12f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "glowS")

    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, spatial(), label = "press")
    val state by animateFloatAsState(if (running) 0f else 1f, spatial(), label = "state")
    val fill by animateColorAsState(if (running) C.Primary else C.TonalStrong, effect(), label = "fill")
    val runA by animateFloatAsState(if (running) 1f else 0f, effect(), label = "runA")
    val path = remember { Path() }

    Box(
        Modifier
            .size(size)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onToggle() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size)) {
            // soft outer glow
            val gr = this.size.minDimension * 0.62f * glowS
            drawCircle(
                Brush.radialGradient(
                    listOf(C.Primary.copy(alpha = glowA * runA), Color.Transparent),
                    center = center, radius = gr
                ),
                radius = gr, center = center
            )
            val cubics = if (press > 0.005f) {
                (if (state < 0.5f) M3EShapes.CookieToCircle else M3EShapes.SunnyToCircle).asCubics(press)
            } else {
                M3EShapes.CookieToSunny.asCubics(state)
            }
            val s = 1f - 0.08f * press
            val inset = this.size.width * (1f - s) / 2f
            rotate(rotation.value) {
                cubics.toComposePath(androidx.compose.ui.geometry.Size(this.size.width * s, this.size.height * s), path)
                path.translate(Offset(inset, inset))
                drawPath(path, fill)
            }
        }
        Crossfade(running, animationSpec = effect(), label = "icon") { r ->
            if (r) LineIcon(Ic.Stop, Color.White, 34.dp) else LineIcon(Ic.Play, C.OnSurfaceVar, 36.dp)
        }
    }
}

// ---------------------------------------------------------------- selection controls

@Composable
fun SegmentedSelector(options: List<Pair<Ic, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val gap = 8.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val itemW = (maxWidth - gap * (options.size - 1)) / options.size
        val x by animateDpAsState((itemW + gap) * selected, spatial(), label = "segX")
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            repeat(options.size) {
                Box(Modifier.width(itemW).height(80.dp).clip(RoundedRectangle(22.dp)).background(C.Tonal))
            }
        }
        Box(
            Modifier.offset(x = x).width(itemW).height(80.dp)
                .clip(RoundedRectangle(22.dp)).background(C.Primary)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { i, (ic, label) ->
                val on = i == selected
                val fg by animateColorAsState(if (on) Color.White else C.OnSurfaceVar, effect(), label = "segFg")
                Column(
                    Modifier.width(itemW).height(80.dp).pressable(0.95f) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    LineIcon(ic, fg, 24.dp, filled = on)
                    Gap(Modifier.height(6.dp))
                    T(label, 13.sp, FontWeight.SemiBold, fg)
                }
            }
        }
    }
}

@Composable
fun FilterChips(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val target = bounds[selected]
    val ix by animateFloatAsState(target?.first ?: 0f, spatial(), label = "chipX")
    val iw by animateFloatAsState(target?.second ?: 0f, spatial(), label = "chipW")
    Box(modifier.horizontalScroll(rememberScrollState())) {
        if (target != null) {
            Box(
                Modifier
                    .offset { IntOffset(ix.roundToInt(), 0) }
                    .width(with(density) { iw.toDp() })
                    .height(38.dp)
                    .clip(Capsule())
                    .background(C.Primary)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { i, label ->
                val on = i == selected
                val bg by animateColorAsState(if (on) Color.Transparent else Color.White, effect(), label = "chipBg")
                val fg by animateColorAsState(if (on) Color.White else C.OnSurfaceVar, effect(), label = "chipFg")
                Box(
                    Modifier
                        .onGloballyPositioned { c ->
                            bounds[i] = c.boundsInParentX()
                        }
                        .height(38.dp)
                        .clip(Capsule())
                        .background(bg)
                        .pressable(0.94f) { onSelect(i) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center
                ) { T(label, 14.sp, FontWeight.SemiBold, fg) }
            }
        }
    }
}

private fun androidx.compose.ui.layout.LayoutCoordinates.boundsInParentX(): Pair<Float, Float> {
    val pos = parentLayoutCoordinates?.localPositionOf(this, Offset.Zero) ?: Offset.Zero
    return pos.x to size.width.toFloat()
}

/** Primary circle with a check that springs in (overshoot) and draws its stroke. */
@Composable
fun CheckBadge(visible: Boolean, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(if (visible) 1f else 0f) }
    val draw = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        if (visible) {
            draw.snapTo(0f)
            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
        } else {
            scale.animateTo(0f, spatial())
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            delay(80)
            draw.animateTo(1f, spring(dampingRatio = 1f, stiffness = 300f))
        }
    }
    val full = remember { Path() }
    val seg = remember { Path() }
    Canvas(modifier.size(size).graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
        if (scale.value <= 0.001f) return@Canvas
        drawCircle(C.Primary)
        val w = this.size.width
        full.reset()
        full.moveTo(w * 0.28f, w * 0.52f)
        full.lineTo(w * 0.44f, w * 0.67f)
        full.lineTo(w * 0.73f, w * 0.36f)
        val pm = PathMeasure()
        pm.setPath(full, false)
        seg.reset()
        pm.getSegment(0f, pm.length * draw.value, seg, true)
        drawPath(seg, Color.White, style = Stroke(w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------- motion helpers

/** Staggered entrance: fade in + rise 16dp, 30ms apart. */
fun Modifier.staggerIn(index: Int): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 30L)
        p.animateTo(1f, spatial())
    }
    val d = LocalDensity.current
    graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * with(d) { 16.dp.toPx() }
    }
}

/** Shimmer sweep while [active]. */
fun Modifier.shimmer(active: Boolean): Modifier = composed {
    val tr = rememberInfiniteTransition(label = "shimmer")
    val x by tr.animateFloat(-1f, 2f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "shimmerX")
    val a by animateFloatAsState(if (active) 1f else 0f, effect(), label = "shimmerA")
    drawWithContent {
        drawContent()
        if (a > 0.01f) {
            drawRect(C.Tonal.copy(alpha = 0.85f * a))
            val w = size.width
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.75f * a), Color.Transparent),
                    start = Offset(w * x - w * 0.5f, 0f),
                    end = Offset(w * x + w * 0.5f, size.height)
                )
            )
        }
    }
}

@Composable
fun CircleIconButton(ic: Ic, onClick: () -> Unit, bg: Color = C.Tonal, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(Capsule()).background(bg).pressable { onClick() },
        contentAlignment = Alignment.Center
    ) { LineIcon(ic, C.OnSurfaceVar, 20.dp) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(text, 18.sp, FontWeight.Bold, C.OnSurface, Modifier.weight(1f))
        trailing()
    }
}

