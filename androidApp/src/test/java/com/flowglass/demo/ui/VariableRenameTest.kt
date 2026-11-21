package com.flowglass.demo.ui

import com.flowglass.engine.LocalVariable
import com.flowglass.engine.Rule
import com.flowglass.engine.RulePresentation
import com.flowglass.engine.RuleUiMetadata
import com.flowglass.engine.VariableScope
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renaming a local variable must only rewrite references that resolve to that same definition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VariableRenameTest {
    private fun cmd(id: Long, title: String, parameter: String): Cmd = Cmd(
        id = id, auto = true, ic = Ic.Bolt, title = title, desc = "",
        actions = listOf(CommandItem(id * 10, "屏幕提示", parameter, Ic.Bolt, "action.toast")),
        enabled = false,
    )

    private fun local(name: String) = LocalVariable(name = name, type = "text", initial = JsonPrimitive(""))

    private fun state(vararg cmds: Cmd, locals: Map<String, List<LocalVariable>> = emptyMap()): AppState {
        val s = AppState()
        s.autoCmds.addAll(cmds)
        s.engineRules = cmds.map { Rule(id = "rule-${it.id}", name = it.title, locals = locals[it.title].orEmpty()) }
        s.engineUi = RuleUiMetadata(
            presentation = cmds.associate { "rule-${it.id}" to RulePresentation(legacyId = it.id) },
        )
        return s
    }

    @Test
    fun localRenameOnlyRewritesItsOwnerIncludingTheQualifiedForm() {
        val s = state(
            cmd(1, "甲", "{shared} + {rule.shared} + {global.shared}"),
            cmd(2, "乙", "{shared}"),
            locals = mapOf("甲" to listOf(local("shared")), "乙" to listOf(local("shared"))),
        )

        s.renameVariable(1L, "shared", "renamed", VariableScope.LOCAL)

        assertEquals(
            "{renamed} + {rule.renamed} + {global.shared}",
            s.autoCmds.first { it.id == 1L }.actions.single().parameter,
        )
        assertEquals(
            "另一条指令的同名局部变量不得被改写",
            "{shared}",
            s.autoCmds.first { it.id == 2L }.actions.single().parameter,
        )
        assertEquals("renamed", s.engineRules.first { it.id == "rule-1" }.locals.single().name)
        assertEquals("shared", s.engineRules.first { it.id == "rule-2" }.locals.single().name)
    }

    @Test
    fun globalRenameSkipsUnqualifiedReferencesShadowedByALocal() {
        val s = state(
            cmd(1, "甲", "{shared} + {global.shared}"),
            cmd(2, "乙", "{shared} + {global.shared}"),
            locals = mapOf("甲" to listOf(local("shared"))),
        )
        s.engineGlobals = mapOf("shared" to JsonPrimitive("x"))

        s.renameVariable(null, "shared", "renamed", VariableScope.GLOBAL)

        assertEquals(
            "被同名局部变量遮蔽的写法保持不动，显式全局前缀跟随改名",
            "{shared} + {global.renamed}",
            s.autoCmds.first { it.id == 1L }.actions.single().parameter,
        )
        assertEquals("{renamed} + {global.renamed}", s.autoCmds.first { it.id == 2L }.actions.single().parameter)
        assertEquals("renamed", s.engineGlobals.keys.single())
    }
}
