package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editor summaries must follow the real parameter, not a cached count or generic label. */
class ActionSummaryTest {
    private fun item(
        typeId: String,
        name: String,
        parameter: String,
        children: List<CommandItem> = emptyList(),
        branches: List<CommandBranch> = emptyList(),
        conditions: List<CommandItem> = emptyList(),
    ) = CommandItem(
        id = 1, name = name, parameter = parameter, ic = Ic.Bolt, typeId = typeId,
        children = children, branches = branches, conditions = conditions,
    )

    private fun cond(typeId: String, name: String, parameter: String) =
        item(typeId, name, parameter)

    @Test
    fun loopSummaryFollowsTheEditedCount() {
        val two = item("block.loop", "循环", "按次数|2", children = listOf(
            item("action.toast", "Toast 提示", "a"),
            item("action.toast", "Toast 提示", "b"),
        ))
        assertEquals("重复 2 次 · 内含 2 步", ActionSummary.summarize(two, 2, open = true))
        val five = two.copy(parameter = "按次数|5")
        assertEquals("重复 5 次 · 内含 2 步", ActionSummary.summarize(five, 2, open = true))
    }

    @Test
    fun whileLoopUsesTheConditionWording() {
        val loop = item("block.loop", "循环", "条件成立时一直循环|3")
        assertEquals("条件成立时重复（最多 1000 次）", ActionSummary.summarize(loop, 1, open = true))
    }

    @Test
    fun ifSummaryReusesTheConditionLabelAndNotesElse() {
        val charging = cond("condition.charging", "充电状态", "charging")
        val plain = item("block.if", "如果", "", conditions = listOf(charging))
        assertEquals("如果 充电中", ActionSummary.summarize(plain, 1, open = true))

        val withElse = plain.copy(branches = listOf(CommandBranch(children = emptyList(), conditions = emptyList())))
        assertEquals("如果 充电中 · 有否则", ActionSummary.summarize(withElse, 1, open = true))

        val network = cond("condition.network_type", "网络类型", "wifi")
        assertEquals("WiFi", ActionSummary.conditionText(network))
    }

    @Test
    fun waitSummaryIncludesConditionAndTimeout() {
        val wait = item(
            "block.wait_until", "等待直到", "30 秒|1 秒",
            conditions = listOf(cond("condition.screen_state", "屏幕状态", "on")),
        )
        assertEquals("等待 已亮屏，最长 30 秒", ActionSummary.summarize(wait, 0, open = true))
        val oneMinute = wait.copy(parameter = "1 分钟|1 秒")
        assertEquals("等待 已亮屏，最长 1 分钟", ActionSummary.summarize(oneMinute, 0, open = true))
    }

    @Test
    fun runRuleShowsTargetAndWaitMode() {
        val wait = item("action.run_rule", "执行另一条指令", "链慢子|true")
        assertEquals("执行「链慢子」· 等待完成", ActionSummary.summarize(wait, 0, open = true))
        val async = wait.copy(parameter = "链慢子|false")
        assertEquals("执行「链慢子」· 发起后继续", ActionSummary.summarize(async, 0, open = true))
    }

    @Test
    fun setVarAndStopShowTheirParameters() {
        val add = item("action.set_var", "设置变量", "n|add|1")
        assertEquals("n 加 1", ActionSummary.summarize(add, 0, open = true))
        val set = add.copy(parameter = "run.n|set|hello")
        assertEquals("run.n 设为 hello", ActionSummary.summarize(set, 0, open = true))
        val stop = item("block.stop", "停止任务", "success|")
        assertEquals("停止任务：按成功结束", ActionSummary.summarize(stop, 0, open = true))
    }

    @Test
    fun collapsedRowsStillShowTheChildCount() {
        val loop = item("block.loop", "循环", "按次数|5", children = listOf(item("action.toast", "Toast", "x")))
        assertTrue(ActionSummary.summarize(loop, 3, open = false).startsWith("已折叠 · 3 项"))
    }
}
