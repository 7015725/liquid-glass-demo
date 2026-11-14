package com.flowglass.demo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule

/**
 * Shared empty state: an icon tile, a short title and one line of guidance. Used wherever a
 * list can legitimately have nothing to show so the screen never looks broken or unfinished.
 */
@Composable
fun EmptyState(
    ic: Ic,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionTag: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(58.dp).clip(PolygonShape(M3EShapes.Clover4)).background(C.Tonal),
            contentAlignment = Alignment.Center,
        ) { LineIcon(ic, C.OnSurfaceVar, 26.dp) }
        T(title, 15.sp, FontWeight.SemiBold, C.OnSurface, textAlign = TextAlign.Center)
        T(hint, 13.sp, color = C.Muted, textAlign = TextAlign.Center, lineHeight = 19.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier.height(44.dp)
                    .clip(Capsule()).background(C.PrimarySoft)
                    .then(if (actionTag != null) Modifier.testTag(actionTag) else Modifier)
                    .editorAction(actionLabel) { onAction() }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { T(actionLabel, 14.sp, FontWeight.SemiBold, C.Primary) }
        }
    }
}

/** Small capsule used for tiers, "即将支持" and other table-stakes metadata. */
@Composable
fun BadgePill(text: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.clip(Capsule()).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, 11.sp, FontWeight.Medium, fg) }
}

/**
 * First-frame state: local storage is read on the IO dispatcher, so the very first composition
 * shows this instead of a bare text line.
 */
@Composable
fun LoadingScreen() {
    val pulse = rememberInfiniteTransition(label = "loading")
    val scale by pulse.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxSize().background(C.Bg).testTag("app-loading"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(88.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(PolygonShape(M3EShapes.Clover4)).background(C.PrimarySoft),
                contentAlignment = Alignment.Center,
            ) { LineIcon(Ic.Bolt, C.Primary, 38.dp) }
            Spacer(Modifier.height(18.dp))
            T("流光自动化", 20.sp, FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(C.Primary, 16.dp)
                Spacer(Modifier.width(8.dp))
                T("正在读取本地指令…", 13.sp, color = C.Muted)
            }
            Spacer(Modifier.height(6.dp))
            T("首次打开需要解析已保存的指令", 12.sp, color = C.Muted)
        }
    }
}
