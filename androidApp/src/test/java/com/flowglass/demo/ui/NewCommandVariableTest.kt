package com.flowglass.demo.ui

import com.flowglass.engine.VariableScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UIE-005 / UIE-006：新指令的局部变量与副本的局部变量都必须真正落到 canonical rule。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewCommandVariableTest {
    @Test
    fun aLocalVariableCanBeCreatedForACommandCreatedInThisProcess() {
        val state = AppState()
        val cmd = state.create(auto = true)

        state.createVariable(VariableScope.LOCAL, cmd.id, "次数", "int", "", "3")

        assertNotNull("新指令必须立即拥有 canonical rule", state.ruleIdFor(cmd.id))
        assertEquals(listOf("次数"), state.localsOf(cmd.id).map { it.name })
    }

    @Test
    fun duplicatingACommandCopiesItsLocalVariables() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.createVariable(VariableScope.LOCAL, cmd.id, "次数", "int", "计数", "3")

        val copyId = requireNotNull(state.duplicate(cmd.id))

        val original = state.ruleIdFor(cmd.id)
        val copied = state.ruleIdFor(copyId)
        assertNotNull(copied)
        assertNotEquals(original, copied)
        assertEquals(state.localsOf(cmd.id), state.localsOf(copyId))
    }
}
