package com.flowglass.demo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor validates every visible card on the main thread. A legal chain with duplicated call
 * edges must not make that validation enumerate the same sub-paths over and over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ValidationScaleTest {
    private fun call(target: String, id: Long) =
        CommandItem(id, "执行 $target", "$target|", Ic.Bolt, "action.run_rule")

    /** R0..R5, each calling the next one 30 times; five edges deep, no cycle. */
    private fun chainState(width: Int = 30, depth: Int = 6): AppState {
        val s = AppState()
        repeat(depth) { level ->
            val actions = if (level == depth - 1) {
                listOf(CommandItem(level * 1000L + 1, "提示", "", Ic.Bolt, "action.toast"))
            } else {
                (1..width).map { edge -> call("R${level + 1}", level * 1000L + edge) }
            }
            s.autoCmds.add(
                Cmd(
                    id = level + 1L, auto = true, ic = Ic.Bolt, title = "R$level", desc = "",
                    actions = actions, enabled = false,
                ),
            )
        }
        return s
    }

    @Test
    fun duplicatedCallEdgesStayLinear() {
        val s = chainState()
        val started = System.nanoTime()
        val issues = s.validationIssues(s.autoCmds.first())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertFalse("合法链条不应报成环：$issues", issues.any { it.contains("成环") })
        assertTrue("六条指令、每条 30 个重复调用，校验耗时 ${elapsedMs}ms，超过 1000ms", elapsedMs < 1000)
    }

    @Test
    fun realCycleIsStillReported() {
        val s = AppState()
        s.autoCmds.add(Cmd(id = 1L, auto = true, ic = Ic.Bolt, title = "甲", desc = "", actions = listOf(call("乙", 11)), enabled = false))
        s.autoCmds.add(Cmd(id = 2L, auto = true, ic = Ic.Bolt, title = "乙", desc = "", actions = listOf(call("甲", 21)), enabled = false))

        val issues = s.validationIssues(s.autoCmds.first())

        assertTrue("真实成环必须继续提示：$issues", issues.any { it.contains("成环") })
    }
}
