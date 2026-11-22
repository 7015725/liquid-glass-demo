package com.flowglass.demo.ui

import com.flowglass.engine.RuleBranch
import com.flowglass.engine.RuleComponent
import com.flowglass.engine.RuleDocument
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * STO-10：规范文档只校验 sidecar/命令/日志 ID，组件树上的重号会被 sidecar 的 map 折叠，
 * 之后按 ID 查找会同时改到多条，甚至与命令/日志 ID 冲突导致下次加载整份文件被拒。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleDocumentIdTest {
    private fun base(): Triple<PersistedAppState, RuleDocument, com.flowglass.engine.Rule> {
        val cmd = Cmd(
            id = 42, auto = false, ic = Ic.Bolt, title = "任务", desc = "",
            actions = listOf(
                CommandItem(501, "开关 WiFi", "开启", Ic.Wifi, "action.wifi_switch"),
                CommandItem(502, "开关 WiFi", "关闭", Ic.Wifi, "action.wifi_switch"),
            ),
        )
        val state = PersistedAppState(emptyList(), listOf(cmd), emptyList(), true, 1)
        val document = state.toRuleDocument()
        return Triple(state, document, document.rules.single())
    }

    @Test
    fun duplicateIdsAcrossContainersAreSplitOnLoad() {
        val (_, document, rule) = base()
        val first = rule.actions[0].copy(id = 700)
        val duplicate = rule.actions[1].copy(id = 700)
        val broken = document.copy(rules = listOf(rule.copy(actions = listOf(first, duplicate))))

        val command = broken.toPersistedAppState().oneTaps.single()
        val ids = command.actions.map { it.id }
        assertEquals("首个出现保留原 ID", 700L, ids[0])
        assertTrue("重复 ID 必须重新分配", ids[0] != ids[1])
        assertTrue(ids.all { it > 0L })
    }

    @Test
    fun aComponentMustNotReuseTheCommandId() {
        val (_, document, rule) = base()
        val collision = rule.actions[0].copy(id = 42L)
        val broken = document.copy(rules = listOf(rule.copy(actions = listOf(collision))))

        val state = broken.toPersistedAppState()
        val command = state.oneTaps.single()
        assertTrue("组件 ID 不得与命令 ID 相同", command.actions.single().id != command.id)
        val items = state.engineUi.presentation.getValue(broken.rules.single().id).items
        assertEquals("一个组件只应有一个 sidecar 键", 1, items.size)
    }

    @Test
    fun anIdNearTheLongLimitDoesNotMakeTheFileUnloadable() {
        val (_, document, rule) = base()
        val extreme = rule.actions[0].copy(id = Long.MAX_VALUE - 1L)
        val broken = document.copy(rules = listOf(rule.copy(actions = listOf(extreme))))

        val command = broken.toPersistedAppState().oneTaps.single()
        val id = command.actions.single().id
        assertTrue("极端 ID 必须被重新分配", id in 1L until Long.MAX_VALUE - 1L)
    }

    @Test
    fun duplicateInNestedBranchesIsSplit() {
        val (_, document, rule) = base()
        val branchChild = RuleComponent(
            id = 800,
            type = "action.toast",
            params = mapOf("content" to JsonPrimitive("hi")),
        )
        val sibling = RuleComponent(
            id = 800,
            type = "action.toast",
            params = mapOf("content" to JsonPrimitive("hi2")),
        )
        val ifBlock = RuleComponent(
            id = 900,
            type = "block.if",
            branches = listOf(
                RuleBranch(children = listOf(branchChild)),
                RuleBranch(children = listOf(sibling)),
            ),
        )
        val broken = document.copy(rules = listOf(rule.copy(actions = listOf(ifBlock))))

        val command = broken.toPersistedAppState().oneTaps.single()
        val ids = command.actions.single().branches.flatMap { branch -> branch.children.map { it.id } }
        assertEquals(2, ids.size)
        assertTrue("嵌套分支里的重号也必须拆开", ids[0] != ids[1])
    }
}
