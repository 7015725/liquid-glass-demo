package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal data class MenuItemSpec(
    val label: String,
    val icon: Ic? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Shared dropdown surface. It has no fixed negative offsets: the provider flips above the anchor
 * and clamps to the window so a menu near an edge is never cut off.
 */
@Composable
internal fun AnchoredMenu(
    anchor: Rect?,
    onDismiss: () -> Unit,
    items: List<MenuItemSpec>,
) {
    if (anchor == null || items.isEmpty()) return
    Popup(
        popupPositionProvider = remember(anchor) { MenuPositionProvider(anchor) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        MenuSurface(items, onDismiss)
    }
}

/** Pure position math, kept separate so edge cases can be tested without a Popup window. */
internal fun menuOffset(
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    marginPx: Int,
    gapPx: Int,
): IntOffset {
    val maxX = (window.width - popup.width - marginPx).coerceAtLeast(marginPx)
    val x = (anchor.right - popup.width).coerceIn(marginPx, maxX)
    val below = anchor.bottom + gapPx
    val above = anchor.top - popup.height - gapPx
    val maxY = (window.height - popup.height - marginPx).coerceAtLeast(marginPx)
    val y = if (below <= maxY) below else above.coerceIn(marginPx, maxY)
    return IntOffset(x, y)
}

/** Records the anchor only while the menu is open, avoiding layout loops in idle screens. */
internal fun Modifier.trackMenuAnchor(state: MutableState<Rect?>, active: Boolean): Modifier =
    onGloballyPositioned { coordinates ->
        if (active) {
            val bounds = coordinates.boundsInWindow()
            if (state.value != bounds) state.value = bounds
        }
    }

private class MenuPositionProvider(private val anchor: Rect) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rect = IntRect(
            left = anchor.left.roundToInt(),
            top = anchor.top.roundToInt(),
            right = anchor.right.roundToInt(),
            bottom = anchor.bottom.roundToInt(),
        )
        return menuOffset(rect, windowSize, popupContentSize, marginPx = 8, gapPx = 6)
    }
}

@Composable
internal fun MenuSurface(
    items: List<MenuItemSpec>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 208.dp,
    itemHeight: Dp = 44.dp,
) {
    Column(
        modifier
            .width(width)
            .shadow(14.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(C.Card)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items.forEach { item ->
            MenuItemRow(item, itemHeight) {
                onDismiss()
                if (item.enabled) item.onClick()
            }
        }
    }
}

@Composable
private fun MenuItemRow(item: MenuItemSpec, height: Dp, onClick: () -> Unit) {
    val color = when {
        !item.enabled -> C.Muted
        item.danger -> C.Bad
        else -> C.OnSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (item.enabled) Modifier
                    .semantics {
                        role = Role.Button
                        contentDescription = item.label
                    }
                    .clickable(onClick = onClick)
                else Modifier.semantics { contentDescription = item.label }
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            LineIcon(item.icon, color, 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        T(item.label, 14.sp, if (item.danger) FontWeight.SemiBold else FontWeight.Medium, color, maxLines = 1)
    }
}
