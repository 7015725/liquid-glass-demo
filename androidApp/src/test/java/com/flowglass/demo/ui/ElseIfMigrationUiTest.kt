package com.flowglass.demo.ui

import com.flowglass.engine.ComponentKind
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleBranch
import com.flowglass.engine.RuleComponent
import com.flowglass.engine.RuleDocument
import com.flowglass.engine.RuleType
import com.flowglass.engine.RuleUiMetadata
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Loading an old else-if chain must expose the same three-section if tree as a new block. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ElseIfMigrationUiTest {
    private fun condition(name: String) = RuleComponent(
        "condition.variable",
        params = mapOf("left" to JsonPrimitive("{$name}"), "op" to JsonPrimitive("eq"), "right" to JsonPrimitive("yes")),
    )

    private fun action(name: String) = RuleComponent("action.toast", params = mapOf("text" to JsonPrimitive(name)))

    @Test
    fun oldElseIfChainBecomesOneElseBranchWithANestedIf() {
        val old = RuleComponent(
            "block.if",
            conditions = listOf(condition("a")),
            children = listOf(action("A")),
            branches = listOf(
                RuleBranch(conditions = listOf(condition("b")), children = listOf(action("B"))),
                RuleBranch(children = listOf(action("Z"))),
            ),
        )
        val command = RuleDocument(
            rules = listOf(Rule("r", "旧数据", type = RuleType.MANUAL, actions = listOf(old))),
            ui = RuleUiMetadata(),
        ).toPersistedAppState().oneTaps.single()
        val block = command.actions.single()
        assertEquals("旧 else-if 必须收进一个否则分支", 1, block.branches.size)
        assertTrue(block.branches.single().conditions.isEmpty())
        val nested = block.branches.single().children.single()
        assertEquals("block.if", nested.typeId)
        assertTrue(nested.conditions.isNotEmpty())
        assertEquals("嵌套 if 的否则保留原 Z", listOf("Z"), nested.branches.single().children.map { it.parameter })
        assertTrue(listOf(block).allItems().none { it.typeId == "block.else_if" })

        val persisted = RuleDocument(
            rules = listOf(Rule("r", "旧数据", type = RuleType.MANUAL, actions = listOf(old))),
            ui = RuleUiMetadata(),
        ).toPersistedAppState()
        val savedBlock = persisted.toRuleDocument().rules.single().actions.single()
        assertEquals("保存时也应只写一个否则", 1, savedBlock.branches.size)
        assertTrue(savedBlock.branches.single().conditions.isEmpty())
        assertEquals("block.if", savedBlock.branches.single().children.single().type)
    }

    @Test
    fun oldElseIfWithoutAnElseStaysWithoutOne() {
        val old = RuleComponent(
            "block.if",
            conditions = listOf(condition("a")),
            children = listOf(action("A")),
            branches = listOf(RuleBranch(conditions = listOf(condition("b")), children = listOf(action("B")))),
        )
        val command = RuleDocument(
            rules = listOf(Rule("r", "旧数据", type = RuleType.MANUAL, actions = listOf(old))),
            ui = RuleUiMetadata(),
        ).toPersistedAppState().oneTaps.single()
        val block = command.actions.single()
        assertEquals(1, block.branches.size)
        assertTrue(block.branches.single().conditions.isEmpty())
        val nested = block.branches.single().children.single()
        assertTrue("没有旧 else 时不能凭空生成一个", nested.branches.isEmpty())
    }

    @Test
    fun directlyConstructedLegacyCommandIsNormalizedWhenLoaded() {
        val state = AppState()
        val cmd = state.create(auto = false)
        val old = CommandItem(
            id = 9001,
            name = "如果",
            parameter = "",
            ic = Ic.Shield,
            typeId = "block.if",
            conditions = mutableListOf(CommandItem(9002, "充电状态", "充电中", Ic.Battery, "condition.charging")),
            children = mutableListOf(CommandItem(9003, "提示", "A", Ic.Message, "action.toast")),
            branches = mutableListOf(CommandBranch(conditions = mutableListOf(
                CommandItem(9004, "充电状态", "充电中", Ic.Battery, "condition.charging")
            ), children = mutableListOf(CommandItem(9005, "提示", "B", Ic.Message, "action.toast")))),
        )
        state.oneTaps[0] = cmd.copy(actions = mutableListOf(old))
        val reloaded = AppState(state.snapshot())
        val block = reloaded.oneTaps.single().actions.single()
        assertEquals(1, block.branches.size)
        assertTrue(block.branches.single().conditions.isEmpty())
        val nested = block.branches.single().children.single()
        assertEquals("block.if", nested.typeId)
        assertTrue(nested.conditions.isNotEmpty())
        assertFalse(listOf(block).allItems().any { it.branches.any { branch -> branch.conditions.isNotEmpty() } })
    }
}
