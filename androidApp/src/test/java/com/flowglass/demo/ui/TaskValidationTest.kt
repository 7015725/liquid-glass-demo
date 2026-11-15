package com.flowglass.demo.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** C2 编辑期提示：不完整块、循环外跳出、目标丢失与自调用成环。 */
class TaskValidationTest {
    private fun item(id: Long, type: String, name: String, parameter: String = "", children: List<CommandItem> = emptyList(), conditions: List<CommandItem> = emptyList()) =
        CommandItem(id, name, parameter, Ic.Bolt, type, children = children, conditions = conditions)

    private fun state(vararg cmds: Cmd): AppState = AppState().apply { oneTaps.clear(); oneTaps.addAll(cmds) }

    private fun cmd(id: Long, title: String, actions: List<CommandItem>) =
        Cmd(id = id, auto = false, ic = Ic.Bolt, title = title, desc = "", actions = actions)

    @Test
    fun incompleteIfReportsConditionAndBody() {
        val state = state(cmd(1, "测试", listOf(item(10, "block.if", "如果"))))
        val issues = state.validationIssues(state.oneTaps[0])
        assertTrue(issues.any { it.contains("缺少条件") })
        assertTrue(issues.any { it.contains("还没有任何步骤") })
    }

    @Test
    fun breakOutsideLoopIsReported() {
        val state = state(cmd(1, "测试", listOf(item(10, "block.break", "跳出循环"))))
        assertTrue(state.validationIssues(state.oneTaps[0]).any { it.contains("不在循环内") })
    }

    @Test
    fun missingCallTargetIsReported() {
        val state = state(cmd(1, "测试", listOf(item(10, "action.run_rule", "执行另一条指令", "不存在的指令|等它执行完再继续"))))
        assertTrue(state.validationIssues(state.oneTaps[0]).any { it.contains("找不到目标指令") })
    }

    @Test
    fun selfCallIsReportedAsCycle() {
        val state = state(cmd(1, "自己", listOf(item(10, "action.run_rule", "执行另一条指令", "自己|等它执行完再继续"))))
        assertTrue(state.validationIssues(state.oneTaps[0]).any { it.contains("成环") })
    }

    @Test
    fun completeIfHasNoIssues() {
        val state = state(
            cmd(1, "测试", listOf(
                item(10, "block.if", "如果", children = listOf(item(11, "a.toast", "屏幕提示", "ok")),
                    conditions = listOf(item(12, "condition.charging", "充电状态", "charging"))),
            )),
        )
        assertTrue(state.validationIssues(state.oneTaps[0]).isEmpty())
    }
}
