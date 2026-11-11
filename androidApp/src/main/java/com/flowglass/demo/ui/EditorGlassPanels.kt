package com.flowglass.demo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun EditorGlassPanel(backdrop: Backdrop, title: String, bottom: Boolean, onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val close by rememberUpdatedState(onClose)
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(if (entered) 1f else 0.96f, spatial(), label = "panelScale")
    val alpha by animateFloatAsState(if (entered) 1f else 0f, effect(), label = "panelAlpha")
    BoxWithConstraints(Modifier.fillMaxSize().testTag("editor-panel").imePadding()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f * alpha))
            .testTag("editor-scrim").semantics {
                role = Role.Button
                contentDescription = "关闭面板"
                onClick { onClose(); true }
            }.pointerInput(Unit) { detectTapGestures { close() } })
        Column(Modifier.align(if (bottom) Alignment.BottomCenter else Alignment.Center)
            .navigationBarsPadding().statusBarsPadding().padding(16.dp).fillMaxWidth()
            .heightIn(max = maxHeight * 0.78f)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .drawBackdrop(backdrop, shape = { RoundedRectangle(28.dp) }, effects = {
                vibrancy(); blur(16.dp.toPx()); lens(18.dp.toPx(), 28.dp.toPx())
            }, onDrawSurface = { drawRect(Color.White.copy(alpha = 0.62f)) })
            .pointerInput(Unit) { detectTapGestures { } }.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T(title, 21.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                EditorIconButton(Ic.Back, "返回", action = onClose)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
internal fun EditorPill(text: String, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(Capsule()).background(C.PrimarySoft)
        .editorAction(label, action).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        T(text, 14.sp, FontWeight.SemiBold, C.Primary)
    }
}
