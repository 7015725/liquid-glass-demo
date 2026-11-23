package com.flowglass.demo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** STO-01：恢复备份是数据导入，不等于授权执行；自动规则与引擎必须保持关闭。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreDisarmTest {
    @Test
    fun restoringABackupKeepsAutomaticExecutionDisabled() {
        val state = AppState()
        val command = state.create(auto = true)
        state.setEnabled(command.id, true)
        assertTrue(state.find(command.id)!!.enabled)
        assertTrue(state.running)

        val restored = state.snapshot().disarmedAfterRestore()

        assertFalse(restored.running)
        assertFalse(restored.engineUi.running)
        assertTrue(restored.autoCmds.isNotEmpty())
        assertTrue(restored.autoCmds.none { it.enabled })
    }
}
