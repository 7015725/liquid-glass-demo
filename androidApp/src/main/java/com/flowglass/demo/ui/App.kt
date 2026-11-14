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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
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

private data class Dest(val title: String, val label: String, val ic: Ic)

private val Dests = listOf(
    Dest("概览", "概览", Ic.Home),
    Dest("自动指令", "自动指令", Ic.Grid),
    Dest("一键指令", "一键指令", Ic.Tap),
    Dest("设置", "设置", Ic.Settings),
)

@Composable
fun FlowGlassApp() {
    val state = rememberStoredAppState()
    if (state == null) {
        Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
            T("正在读取本地指令…", 16.sp, color = C.OnSurfaceVar)
        }
        return
    }

    // Root tier gates the editor's action catalog, so seed it once at startup instead of
    // waiting for the user to open settings: the persisted grant makes this prompt-free.
    LaunchedEffect(Unit) { if (state.root == null) state.root = com.flowglass.demo.system.Root.check(request = false) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(state.running, state.autoCmds.count { it.enabled }) {
        if (state.running && state.autoCmds.any { it.enabled }) {
            com.flowglass.demo.system.trigger.TriggerService.ensureStarted(appContext)
        }
    }
    val backdrop = rememberLayerBackdrop()
    var tab by remember { mutableIntStateOf(0) }
    val scrolls = remember { mutableMapOf<Int, ScrollState>() }
    // settings sub pages get their own page key so each keeps its own scroll position
    val pageKey = if (tab == 3) 30 + state.settingsPage else tab
    fun scrollOf(key: Int) = scrolls.getOrPut(key) { ScrollState(0) }
    fun titleOf(key: Int) = if (key >= 30) SettingsPage.title(key - 30) else Dests[key].title
    BackHandler(enabled = tab == 3 && state.settingsPage != 0) { state.settingsPage = 0 }
    BackHandler(enabled = state.query != null) { state.query = null }
    BackHandler(enabled = state.menu != null || state.renaming != null) {
        state.menu = null
        state.renaming = null
    }
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
    LaunchedEffect(pageKey) { navVisible = true }

    // large title collapses into the top bar
    val currentScroll = scrollOf(pageKey)
    val collapse by remember(currentScroll) {
        derivedStateOf {
            val px = currentScroll.value.toFloat()
            val start = with(density) { 20.dp.toPx() }
            val range = with(density) { 44.dp.toPx() }
            ((px - start) / range).coerceIn(0f, 1f)
        }
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Box(Modifier.fillMaxSize().then(if (state.editing != null || state.menu != null || state.renaming != null) Modifier.clearAndSetSemantics { } else Modifier)) {
        // ---------------- content (also the backdrop the glass samples)
        Box(
            Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
                .background(C.Bg)
                .nestedScroll(connection)
        ) {
            AnimatedContent(
                targetState = pageKey,
                transitionSpec = {
                    (fadeIn(effect()) + scaleIn(spatial(), initialScale = 0.96f)) togetherWith fadeOut(effect())
                },
                label = "page"
            ) { page ->
                PageColumn(
                    scrollOf(page), statusTop, navBottom, titleOf(page),
                    collapse = { if (page == pageKey) collapse else 0f },
                    onBack = if (page > 30) ({ state.settingsPage = 0 }) else null
                ) {
                    StorageErrorNotice(state.storageError)
                    when {
                        page == 0 -> HomeScreen(state) { tab = it }
                        page == 1 -> AutoCommandsScreen(state)
                        page == 2 -> OneTapCommandsScreen(state)
                        else -> SettingsHost(state, page - 30)
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
                titleOf(pageKey), 18.sp, FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 20.dp)
            )
        }

        // ---------------- top-right glass capsule with two actions
        if (state.query == null) {
            LiquidButton(
                onClick = {},
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = statusTop + 8.dp, end = 16.dp),
                surfaceColor = Color.White.copy(alpha = 0.5f)
            ) {
                Box(
                    Modifier.size(40.dp).clip(Capsule()).pressable {
                        if (tab != 1 && tab != 2) tab = 1
                        state.query = ""
                    },
                    contentAlignment = Alignment.Center
                ) { LineIcon(Ic.Search, C.OnSurface, 22.dp) }
                Box(Modifier.width(1.dp).height(18.dp).background(C.Divider))
                Box(
                    Modifier.size(40.dp).clip(Capsule()).pressable {
                        if (tab == 2) {
                            state.openEditor(state.create(auto = false).id)
                        } else {
                            tab = 1
                            state.openEditor(state.create(auto = true).id)
                        }
                    },
                    contentAlignment = Alignment.Center
                ) { LineIcon(Ic.Plus, C.OnSurface, 22.dp) }
            }
        }
        SearchBarOverlay(
            state, backdrop,
            Modifier.align(Alignment.TopCenter).padding(top = statusTop + 6.dp, start = 16.dp, end = 16.dp)
        )

        // ---------------- thin capsule scrollbar on the grid page
        if (tab == 1) ScrollThumb(scrollOf(1), statusTop, Modifier.align(Alignment.TopEnd))

        // ---------------- floating liquid-glass bottom navigation
        if (state.editing == null) Box(
            Modifier
                .testTag("bottom-navigation")
                .align(Alignment.BottomCenter)
                .graphicsLayer { translationY = navHide * (96.dp.toPx() + navBottom.toPx()) }
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
        ) {
            LiquidBottomTabs(
                selectedTabIndex = { tab },
                onTabSelected = { if (it == tab && it == 3) state.settingsPage = 0; tab = it },
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
        CommandEditorHost(state) { destination ->
            // Edits are already in the persisted AppState; close overlays before changing tabs.
            state.closeEditor()
            state.settingsPage = destination
            tab = 3
        }
        ActionEditorHost(state)
        PermissionPromptHost(state)
        PermissionResumeEffect(state)

        // ---------------- undo bar, long-press menu and rename dialog float above everything
        UndoBar(
            state, backdrop,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 92.dp)
        )
        CommandMenuOverlay(state, backdrop)
        RenameOverlay(state, backdrop)

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
    onBack: (() -> Unit)?,
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
            if (onBack != null) {
                Box(
                    Modifier.graphicsLayer { alpha = 1f - collapse() }
                        .size(40.dp).clip(Capsule()).background(C.Card).pressable { onBack() },
                    contentAlignment = Alignment.Center
                ) { LineIcon(Ic.Back, C.OnSurface, 22.dp) }
                Spacer(Modifier.width(10.dp))
            }
            T(
                title, if (onBack != null) 28.sp else 32.sp, FontWeight.Bold,
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
