package com.flowglass.demo.system.exec

import com.flowglass.engine.ExecutionLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerHeadlineTest {
    @Test
    fun reportsRootForActionsAndLsposedForEventsSeparately() {
        val headline = LayerHeadline.of(
            LayerStatusSnapshot(
                normal = LayerState.READY,
                root = LayerState.READY,
                lsposed = LayerState.READY,
            )
        )

        assertEquals(ExecutionLayer.ROOT, headline.action)
        assertEquals(ExecutionLayer.XPOSED, headline.event)
        assertEquals("Root / LSPosed", headline.big)
        assertEquals("动作：Root · 事件：LSPosed", headline.pill)
        assertFalse(headline.normalOnly)
    }

    @Test
    fun reportsNormalOnlyWhenNoHigherLayerIsReady() {
        val headline = LayerHeadline.of(
            LayerStatusSnapshot(
                normal = LayerState.READY,
                root = LayerState.UNAVAILABLE,
                lsposed = LayerState.UNAVAILABLE,
            )
        )

        assertTrue(headline.normalOnly)
        assertEquals("普通", headline.big)
        assertEquals("层", headline.unit)
        assertEquals("普通可用", headline.pill)
    }

    @Test
    fun usesRootForBothPathsWhenLsposedIsUnavailable() {
        val headline = LayerHeadline.of(
            LayerStatusSnapshot(
                normal = LayerState.READY,
                root = LayerState.READY,
                lsposed = LayerState.UNAVAILABLE,
            )
        )

        assertEquals(ExecutionLayer.ROOT, headline.action)
        assertEquals(ExecutionLayer.ROOT, headline.event)
        assertEquals("Root", headline.big)
        assertEquals("动作：Root · 事件：Root", headline.pill)
    }

    @Test
    fun reportsEventOnlyLsposedAlongsideNormalActions() {
        val headline = LayerHeadline.of(
            LayerStatusSnapshot(
                normal = LayerState.READY,
                root = LayerState.UNAVAILABLE,
                lsposed = LayerState.READY,
            )
        )

        assertEquals(ExecutionLayer.NORMAL, headline.action)
        assertEquals(ExecutionLayer.XPOSED, headline.event)
        assertEquals("普通 / LSPosed", headline.big)
        assertEquals("动作：普通 · 事件：LSPosed", headline.pill)
    }
}
