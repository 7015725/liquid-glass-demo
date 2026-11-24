package com.flowglass.demo.system.exec

import com.flowglass.demo.ui.VariableOption
import com.flowglass.engine.ParamExpansion
import com.flowglass.engine.ParamTemplate
import com.flowglass.engine.VariableScope
import com.flowglass.engine.VariableType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialVariablesTest {
    private fun option(
        name: String,
        scope: VariableScope,
        type: VariableType,
        value: String,
    ) = VariableOption(name, scope, type, "", value, 0)

    private fun sample() = TrialVariables.from(
        listOf(
            option("局部", VariableScope.LOCAL, VariableType.TEXT, "甲"),
            option("数字", VariableScope.GLOBAL, VariableType.INT, "42"),
            option("开关", VariableScope.GLOBAL, VariableType.BOOLEAN, "真"),
            option("上一步结果", VariableScope.BUILTIN, VariableType.TEXT, "运行时提供"),
            option("输出", VariableScope.OUTPUT, VariableType.TEXT, "运行时提供"),
        ),
    )

    @Test
    fun readsSavedLocalsAndGlobalsButNotRuntimeOnlyScopes() {
        val trial = sample()

        assertEquals("甲", trial.read("局部"))
        assertEquals(42L, trial.read("global.数字"))
        assertEquals(true, trial.read("开关"))
        assertNull(trial.read("上一步结果"))
        assertNull(trial.read("输出"))
        assertEquals(setOf("局部", "数字", "开关"), trial.names())
    }

    @Test
    fun writesStayInTheTrialSnapshot() {
        val trial = sample()

        trial.write("rule.局部", "乙")
        trial.write("global.数字", 7L)

        assertEquals("乙", trial.read("局部"))
        assertEquals(7L, trial.read("数字"))
        assertEquals("乙", trial.forTemplate()["rule.局部"])
        assertEquals(7L, trial.forTemplate()["global.数字"])
    }

    @Test
    fun shellTemplateSeesTheSameSnapshot() {
        val trial = sample()

        val expanded = ParamTemplate.expand(
            "echo {局部} {global.数字} {开关}",
            trial.forTemplate(),
            ParamExpansion.SHELL_QUOTED,
        )

        assertTrue(expanded, expanded.contains("甲"))
        assertTrue(expanded, expanded.contains("42"))
        assertTrue(expanded, expanded.contains("true"))
        assertFalse(expanded, expanded.contains("{局部}"))
        assertFalse(expanded, expanded.contains("{global.数字}"))
    }
}
