package com.flowglass.demo.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Keeps the 44dp visual / 48dp touch contract from drifting while headers are refactored. */
class TopBarLayoutTest {
    @Test
    fun headerControlUsesA44dpSurfaceInsideA48dpTouchTarget() {
        assertEquals(44.dp, TopBarControlVisual)
        assertEquals(48.dp, TopBarControlTouch)
    }
}
