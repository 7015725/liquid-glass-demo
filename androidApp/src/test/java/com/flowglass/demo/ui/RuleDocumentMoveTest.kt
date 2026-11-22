package com.flowglass.demo.ui

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * STO-09：步骤可以跨容器移动。旧实现只在目标旧父节点的孩子里找同 ID，找不到就按位置借一个
 * 同类型兄弟，移动后会拿到别人的 params，未在 UI 投影里的扩展键直接丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleDocumentMoveTest {
    private fun command(child: CommandItem, secondLoop: CommandItem): Cmd = Cmd(
        id = 42,
        auto = false,
        ic = Ic.Bolt,
        title = "任务",
        desc = "",
        actions = listOf(
            CommandItem(902, "循环", "按次数|3", Ic.Puzzle, "block.loop", children = listOf(child)),
            secondLoop,
        ),
    )

    private fun seededDocument(): Pair<PersistedAppState, Long> {
        val child = CommandItem(901, "开关 WiFi", "开启", Ic.Wifi, "action.wifi_switch")
        val second = CommandItem(903, "循环", "按次数|3", Ic.Puzzle, "block.loop")
        val base = PersistedAppState(
            autoCmds = emptyList(),
            oneTaps = listOf(command(child, second)),
            logs = emptyList(),
            running = true,
            mode = 1,
        )
        val document = base.toRuleDocument()
        val rule = document.rules.single()
        val loop = rule.actions.first()
        val canonical = loop.children.single()
        // 模拟规范文档里的扩展参数：它不在参数表单的投影里。
        val extended = canonical.copy(
            params = canonical.params + ("keep" to JsonPrimitive("原始值")),
        )
        val seeded = document.copy(
            rules = listOf(
                rule.copy(actions = listOf(loop.copy(children = listOf(extended)), rule.actions[1])),
            ),
        )
        return seeded.toPersistedAppState() to canonical.id!!
    }

    private fun PersistedAppState.withOneTaps(commands: List<Cmd>) = PersistedAppState(
        autoCmds = autoCmds,
        oneTaps = commands,
        logs = logs,
        running = running,
        mode = mode,
        engineRules = engineRules,
        engineUi = engineUi,
        engineGlobals = engineGlobals,
        engineGlobalMeta = engineGlobalMeta,
    )

    @Test
    fun movingBetweenLoopsMustPreserveExtensionParams() {
        val (state, childId) = seededDocument()
        val cmd = state.oneTaps.single()
        val from = cmd.actions[0]
        val to = cmd.actions[1]
        val movedChild = from.children.single()
        assertEquals(childId, movedChild.id)

        val moved = cmd.copy(
            actions = listOf(
                from.copy(children = emptyList()),
                to.copy(children = listOf(movedChild)),
            ),
        )

        val result = state.withOneTaps(listOf(moved)).toRuleDocument().rules.single()
        val action = result.actions[1].children.single()
        assertEquals(childId, action.id)
        assertEquals("原始值", (action.params["keep"] as? JsonPrimitive)?.content)
    }

    @Test
    fun movingIntoAnEmptyContainerDoesNotBorrowASibling() {
        val (state, childId) = seededDocument()
        val cmd = state.oneTaps.single()
        // 目标容器原本为空，没有任何“同位置兄弟”可借。
        val moved = cmd.copy(
            actions = listOf(
                cmd.actions[0].copy(children = emptyList()),
                cmd.actions[1].copy(children = cmd.actions[0].children),
            ),
        )
        val result = state.withOneTaps(listOf(moved)).toRuleDocument().rules.single()
        val action = result.actions[1].children.single()
        assertEquals(childId, action.id)
        assertNotNull(action.params["keep"])
    }

    @Test
    fun aLegacyItemWithoutIdStillFallsBackToItsPosition() {
        val child = CommandItem(0, "开关 WiFi", "开启", Ic.Wifi, "action.wifi_switch")
        val second = CommandItem(0, "循环", "按次数|3", Ic.Puzzle, "block.loop")
        val base = PersistedAppState(
            autoCmds = emptyList(),
            oneTaps = listOf(command(child, second)),
            logs = emptyList(),
            running = true,
            mode = 1,
        )
        val rule = base.toRuleDocument().rules.single()
        assertEquals(1, rule.actions[0].children.size)
        assertEquals(0, rule.actions[1].children.size)
    }
}
