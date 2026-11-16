package com.flowglass.demo.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Position math for the one shared dropdown: right-align to the anchor, flip, then clamp. */
class AnchoredMenuTest {
    private val window = IntSize(1080, 2340)

    @Test
    fun opensBelowAndRightAlignsWithTheAnchor() {
        val offset = menuOffset(
            anchor = IntRect(1000, 1000, 1080, 1100),
            window = window,
            popup = IntSize(208, 200),
            marginPx = 8,
            gapPx = 6,
        )
        assertEquals(IntOffset(864, 1106), offset)
    }

    @Test
    fun nearTheRightEdgeShiftsLeftInsteadOfBeingCutOff() {
        val offset = menuOffset(
            anchor = IntRect(0, 300, 40, 340),
            window = window,
            popup = IntSize(208, 200),
            marginPx = 8,
            gapPx = 6,
        )
        assertEquals(8, offset.x)
    }

    @Test
    fun nearTheBottomFlipsAboveTheAnchor() {
        val offset = menuOffset(
            anchor = IntRect(1000, 2200, 1080, 2300),
            window = window,
            popup = IntSize(208, 200),
            marginPx = 8,
            gapPx = 6,
        )
        assertEquals(1994, offset.y)
    }

    @Test
    fun oversizedPopupStaysInsideTheWindowMargins() {
        val offset = menuOffset(
            anchor = IntRect(500, 1000, 600, 1100),
            window = window,
            popup = IntSize(2000, 3000),
            marginPx = 8,
            gapPx = 6,
        )
        assertEquals(IntOffset(8, 8), offset)
    }
}
