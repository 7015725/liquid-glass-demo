package com.flowglass.demo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.flowglass.demo.liquid.LiquidButton
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule

/**
 * Standalone modal surface; deliberately not connected to an app page yet.
 * Keep this call in composition and change [visible] to retain saveable content across closing.
 * The caller owns dismissal (and may refuse it for a dirty form). Content receives the sheet's
 * real surface backdrop for LiquidButton and other existing glass controls. Do not nest another
 * vertical scrolling container in [content]; the header stays fixed while the body scrolls.
 */
@Composable
internal fun GlassSheet(
    visible: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.85f,
    windowInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
    content: @Composable ColumnScope.(Backdrop) -> Unit,
) {
    require(maxHeightFraction > 0f && maxHeightFraction <= 1f) { "Sheet height fraction must be in (0, 1]" }
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    val savedContent = rememberSaveableStateHolder()
    if (!state.currentState && !state.targetState) return

    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnClickOutside = false)) {
        // This full-window scrim owns dimming; avoid a second platform dim layer.
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
            onDispose { }
        }
        BackHandler(onBack = onDismissRequest)
        val scrimAlpha by animateFloatAsState(if (visible) 0.45f else 0f, tween(220), label = "sheetScrim")
        Box(Modifier.fillMaxSize().testTag("glass-sheet-overlay")) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha))
                .testTag("glass-sheet-scrim")
                .semantics { contentDescription = "关闭$title" }
                .clickable(onClick = onDismissRequest))
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(windowInsets)) {
                val maxSheetHeight = maxHeight * maxHeightFraction
                AnimatedVisibility(state, modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(tween(260)) { it },
                    exit = slideOutVertically(tween(220)) { it }) {
                    savedContent.SaveableStateProvider("content") {
                        val backdrop = rememberLayerBackdrop()
                        val scroll = rememberScrollState()
                        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                        Box(modifier.fillMaxWidth().heightIn(max = maxSheetHeight).clip(shape)
                            .testTag("glass-sheet")
                            // Shield non-interactive header/padding taps without adding a fake click action.
                            .pointerInput(Unit) { detectTapGestures { } }
                            .semantics { paneTitle = title; dismiss { onDismissRequest(); true } }) {
                            Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(C.Card))
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 8.dp)
                                    .size(40.dp, 4.dp).clip(Capsule()).background(C.TonalStrong)
                                    .testTag("glass-sheet-handle"))
                                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    T(title, 20.sp, FontWeight.Bold,
                                        modifier = Modifier.weight(1f).semantics { heading() })
                                    LiquidButton(onDismissRequest, backdrop,
                                        modifier = Modifier.size(48.dp).testTag("glass-sheet-close")
                                            .semantics { contentDescription = "关闭面板" },
                                        isInteractive = false, surfaceColor = C.Tonal) {
                                        Canvas(Modifier.size(16.dp)) {
                                            val start = size.width * 0.15f
                                            val end = size.width * 0.85f
                                            drawLine(C.OnSurfaceVar, Offset(start, start), Offset(end, end), 2.dp.toPx(), StrokeCap.Round)
                                            drawLine(C.OnSurfaceVar, Offset(start, end), Offset(end, start), 2.dp.toPx(), StrokeCap.Round)
                                        }
                                    }
                                }
                                Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                                    .verticalScroll(scroll).padding(bottom = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    content(backdrop)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
