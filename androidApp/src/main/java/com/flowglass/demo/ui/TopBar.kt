package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule

/** Shared header tokens: 44dp of visible surface inside a 48dp touch target. */
internal val TopBarControlVisual: Dp = 44.dp
internal val TopBarControlTouch: Dp = 48.dp

@Composable
internal fun TopBarIconButton(
    ic: Ic,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(TopBarControlTouch)
            .editorAction(label, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(TopBarControlVisual)
                .clip(Capsule())
                .background(C.Card),
            contentAlignment = Alignment.Center,
        ) {
            LineIcon(ic, C.OnSurface, 22.dp)
        }
    }
}

/**
 * The one compact header used by the command editor, block pages and parameter pages.
 * The title slot is vertically centered by construction; callers can supply an editable field.
 */
@Composable
internal fun DetailTopBar(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(TopBarControlTouch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TopBarIconButton(Ic.Back, "返回", onClick = onBack)
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                title()
                if (subtitle != null) {
                    T(subtitle, 12.sp, color = C.Muted, maxLines = 1)
                }
            }
        }
        trailing()
    }
}

@Composable
internal fun TopBarTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(TopBarControlTouch)
            .editorAction(label, onClick)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(TopBarControlVisual)
                .clip(Capsule())
                .background(C.PrimarySoft)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            T(label, 13.sp, FontWeight.SemiBold, C.Primary, maxLines = 1)
        }
    }
}

/** Large typographic header kept for root pages; detail pages use [DetailTopBar]. */
@Composable
internal fun LargePageHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    collapse: () -> Float = { 0f },
    fontSize: TextUnit = 28.sp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(TopBarControlTouch)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(Modifier.graphicsLayer { alpha = 1f - collapse() }) {
                TopBarIconButton(Ic.Back, "返回", onClick = onBack)
            }
            Spacer(Modifier.width(10.dp))
        }
        T(
            title,
            fontSize,
            FontWeight.Bold,
            modifier = Modifier.graphicsLayer {
                val c = collapse()
                alpha = 1f - c
                val sc = 1f - 0.25f * c
                scaleX = sc
                scaleY = sc
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            },
        )
    }
}

/** Header used inside glass sheets: back sits on the trailing edge, like the command menu. */
@Composable
internal fun SheetTopBar(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(TopBarControlTouch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(title, 21.sp, FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
        TopBarIconButton(Ic.Back, "返回", onClick = onClose)
    }
}
