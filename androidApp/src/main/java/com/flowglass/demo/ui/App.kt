package com.flowglass.demo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.liquid.LiquidBottomTab
import com.flowglass.demo.liquid.LiquidBottomTabs
import com.flowglass.demo.liquid.LiquidButton
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

@Stable
class AppState {
    var running by mutableStateOf(true)
    var uptime by mutableIntStateOf(2 * 3600 + 41 * 60 + 7)
    var triggers by mutableIntStateOf(1284)
    var mode by mutableIntStateOf(1)
    var accessibility by mutableStateOf(true)
    var category by mutableIntStateOf(0)
    var selectedTasks by mutableStateOf(setOf(0, 2, 4))
    var testRun by mutableIntStateOf(0)
    var plan by mutableIntStateOf(5)
}

private data class Dest(val title: String, val label: String, val ic: Ic)

private val Dests = listOf(
    Dest("概览", "概览", Ic.Home),
    Dest("任务库", "任务", Ic.Grid),
    Dest("方案", "方案", Ic.List),
    Dest("设置", "设置", Ic.Settings),
)

@Composable
fun FlowGlassApp() {
    val state = remember { AppState() }
    val backdrop = rememberLayerBackdrop()
    var tab by remember { mutableIntStateOf(0) }
    val scrolls = List(Dests.size) { rememberScrollState() }
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // bottom bar sinks on scroll-down, floats back on scroll-up
    var navVisible by remember { mutableStateOf(true) }
    val navHide by animateFloatAsState(if (navVisible) 0f else 1f, spatial(), label = "navHide")
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -4f) navVisible = false
                else if (available.y > 4f) navVisible = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(tab) { navVisible = true }

    // large title collapses into the top bar
    val collapse by remember {
        derivedStateOf {
            val px = scrolls[tab].value.toFloat()
            val start = with(density) { 20.dp.toPx() }
            val range = with(density) { 44.dp.toPx() }
            ((px - start) / range).coerceIn(0f, 1f)
        }
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        // ---------------- content (also the backdrop the glass samples)
        Box(
            Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
                .background(C.Bg)
                .nestedScroll(connection)
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(effect()) + scaleIn(spatial(), initialScale = 0.96f)) togetherWith fadeOut(effect())
                },
                label = "page"
            ) { page ->
                PageColumn(scrolls[page], statusTop, navBottom, Dests[page].title, collapse = { if (page == tab) collapse else 0f }) {
                    when (page) {
                        0 -> HomeScreen(state)
                        1 -> TasksScreen(state)
                        2 -> PlansScreen(state)
                        else -> SettingsScreen(state)
                    }
                }
            }
        }

        // ---------------- glass top bar that fades in once the title collapses
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusTop + 64.dp)
                .graphicsLayer { alpha = collapse }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = {
                        vibrancy()
                        blur(18.dp.toPx())
                    },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = { drawRect(C.Bg.copy(alpha = 0.55f)) }
                )
        ) {
            T(
                Dests[tab].title, 18.sp, FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 20.dp)
            )
        }

        // ---------------- top-right glass capsule with two actions
        LiquidButton(
            onClick = {},
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = statusTop + 8.dp, end = 16.dp),
            surfaceColor = Color.White.copy(alpha = 0.5f)
        ) {
            LineIcon(Ic.Search, C.OnSurface, 22.dp)
            Box(Modifier.width(1.dp).height(18.dp).background(C.Divider))
            LineIcon(Ic.Plus, C.OnSurface, 22.dp)
        }

        // ---------------- thin capsule scrollbar on the grid page
        if (tab == 1) ScrollThumb(scrolls[1], statusTop, Modifier.align(Alignment.TopEnd))

        // ---------------- floating liquid-glass bottom navigation
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer { translationY = navHide * (96.dp.toPx() + navBottom.toPx()) }
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
        ) {
            LiquidBottomTabs(
                selectedTabIndex = { tab },
                onTabSelected = { tab = it },
                backdrop = backdrop,
                tabsCount = Dests.size,
            ) {
                Dests.forEachIndexed { i, d ->
                    LiquidBottomTab({ tab = i }) {
                        NavIcon(d.ic, selected = tab == i)
                        T(d.label, 11.sp, FontWeight.Medium, C.OnSurfaceVar)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavIcon(ic: Ic, selected: Boolean) {
    val s = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            s.animateTo(1f, keyframes {
                durationMillis = 360
                1.15f at 140
                1f at 360
            })
        }
    }
    Box(Modifier.graphicsLayer { scaleX = s.value; scaleY = s.value }) {
        LineIcon(ic, C.OnSurfaceVar, 24.dp, filled = selected)
    }
}

@Composable
private fun PageColumn(
    scroll: ScrollState,
    statusTop: androidx.compose.ui.unit.Dp,
    navBottom: androidx.compose.ui.unit.Dp,
    title: String,
    collapse: () -> Float,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(statusTop + 8.dp))
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T(
                title, 32.sp, FontWeight.Bold,
                modifier = Modifier.graphicsLayer {
                    val c = collapse()
                    alpha = 1f - c
                    val sc = 1f - 0.25f * c
                    scaleX = sc; scaleY = sc
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                }
            )
        }
        content()
        Spacer(Modifier.height(navBottom + 100.dp))
    }
}

@Composable
private fun ScrollThumb(scroll: ScrollState, statusTop: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val visible by animateFloatAsState(if (scroll.isScrollInProgress) 1f else 0.35f, effect(), label = "thumb")
    val frac = if (scroll.maxValue > 0) scroll.value / scroll.maxValue.toFloat() else 0f
    val trackTop = statusTop + 80.dp
    val trackH = 380.dp
    val thumbH = 44.dp
    Box(
        modifier
            .padding(top = trackTop + (trackH - thumbH) * frac, end = 4.dp)
            .graphicsLayer { alpha = visible }
            .size(4.dp, thumbH)
            .clip(Capsule())
            .background(C.OnSurface.copy(alpha = 0.35f))
    )
}
