package com.flowglass.demo.system.exec

import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import com.flowglass.engine.GlobalVariableMeta
import com.flowglass.engine.LocalVariable
import com.flowglass.engine.Rule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 真实 Rhino 执行下的模板字符串与作用域写法。扫描器的单元测试在引擎模块，这里验证
 * `ExecutionEngine → ScriptTask` 的整条链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptTemplateVariableTest {
    private fun engine() = ExecutionEngine(RuntimeEnvironment.getApplication()).apply {
        ruleResolver = { id ->
            Rule(
                id = "rule-$id",
                name = "模板变量",
                actions = emptyList(),
                locals = listOf(LocalVariable("局部", "text", JsonPrimitive("局部值"))),
            )
        }
        globalsProvider = {
            mapOf("全局" to JsonPrimitive("全局值")) to mapOf("全局" to GlobalVariableMeta("text"))
        }
    }

    private fun cmd(vararg scripts: String) = Cmd(
        id = 9401,
        auto = false,
        ic = Ic.Bolt,
        title = "模板变量",
        desc = "",
        actions = scripts.mapIndexed { index, script ->
            CommandItem((index + 1).toLong(), "执行 JavaScript", script, Ic.Bolt, "action.script")
        },
    )

    private fun run(vararg scripts: String): RunResult =
        runBlocking { engine().run(cmd(*scripts), "测试") }

    private fun result(run: RunResult): String =
        run.steps.last().detail.substringAfter("脚本返回：", "").trim()

    @Test
    fun templateExpressionReadsALocalVariable() {
        val run = run("var v = `x=\${ {局部} }`; v;")

        assertTrue(run.detail, run.ok)
        assertEquals("x=局部值", result(run))
    }

    @Test
    fun templateExpressionReadsThePreviousStep() {
        val run = run("\"第一\";", "var v = `x=\${ {上一步结果} }`; v;")

        assertTrue(run.detail, run.ok)
        assertTrue(result(run), result(run).startsWith("x=脚本返回：第一"))
    }

    @Test
    fun templateTextKeepsTheBracesLiteral() {
        val run = run("var v = `x={局部}`; v;")

        assertTrue(run.detail, run.ok)
        assertEquals("x={局部}", result(run))
    }

    @Test
    fun scopedTokensReadGlobalsAndLocals() {
        val run = run("String({global.全局}) + '|' + String({rule.局部});")

        assertTrue(run.detail, run.ok)
        assertEquals("全局值|局部值", result(run))
    }

    @Test
    fun objectLiteralInsideATemplateExpressionStillRuns() {
        val run = run("var v = `x=\${ {a:1}.a }`; v;")

        assertTrue(run.detail, run.ok)
        assertEquals("x=1", result(run))
    }
}
