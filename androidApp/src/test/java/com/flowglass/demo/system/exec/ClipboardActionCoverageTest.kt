package com.flowglass.demo.system.exec

import com.flowglass.engine.BuiltInActions
import com.flowglass.engine.ExecutionLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardActionCoverageTest {
    @Test
    fun globalClipboardActionExistsAndHasANormalImplementation() {
        val meta = BuiltInActions.metadata.single { it.typeId == "action.open_clipboard_history" }
        assertEquals("全局剪贴板", meta.name)
        assertEquals(listOf("overlay"), meta.permissions)
        assertTrue(meta.supports.any { it.layer == ExecutionLayer.NORMAL })
        assertTrue(ActionLayers.hasImplementation(meta.typeId, ExecutionLayer.NORMAL))
    }
}
