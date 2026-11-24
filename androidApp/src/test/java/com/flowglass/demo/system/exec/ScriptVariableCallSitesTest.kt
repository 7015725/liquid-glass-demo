package com.flowglass.demo.system.exec

import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import com.flowglass.engine.GlobalVariableMeta
import com.flowglass.engine.LocalVariable
import com.flowglass.engine.Rule
import com.flowglass.engine.VariableDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * JavaScript 动作通过真实 [ExecutionEngine] → [ScriptTask] 读取系统变量/局部变量/全局变量，
 * 与「动作参数」「Shell」两个调用点共用同一份运行作用域。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptVariableCallSitesTest {
    private class Writes {
        var rule: Map<String, Any?> = emptyMap()
        var global: Map<String, Any?> = emptyMap()
        var ruleDefinitions: List<VariableDefinition> = emptyList()
        var globalDefinitions: List<VariableDefinition> = emptyList()
    }

    private fun engine(
        writes: Writes = Writes(),
        locals: List<LocalVariable> = emptyList(),
        globals: Map<String, JsonElement> = emptyMap(),
        meta: Map<String, GlobalVariableMeta> = emptyMap(),
    ): ExecutionEngine = ExecutionEngine(RuntimeEnvironment.getApplication()).apply {
        ruleResolver = { id -> Rule(id = "rule-$id", name = "脚本变量", actions = emptyList(), locals = locals) }
        globalsProvider = { globals to meta }
        variableWriter = { _, rule, global, _ ->
            writes.rule = rule
            writes.global = global
        }
        variableDefinitionWriter = { _, rule, global, _ ->
            writes.ruleDefinitions = rule
            writes.globalDefinitions = global
        }
    }

    private fun cmd(vararg scripts: String) = Cmd(
        id = 9101,
        auto = false,
        ic = Ic.Bolt,
        title = "脚本变量",
        desc = "",
        actions = scripts.mapIndexed { index, script ->
            CommandItem((index + 1).toLong(), "执行脚本", script, Ic.Bolt, "action.script")
        },
    )

    private fun run(engine: ExecutionEngine, vararg scripts: String): RunResult =
        runBlocking { engine.run(cmd(*scripts), "测试") }

    private fun result(run: RunResult): String =
        run.steps.last().detail.substringAfter("脚本返回：", "").trim()

    @Test
    fun systemVariableTokenBindsThePreviousStep() {
        val run = run(
            engine(),
            "\"第一\";",
            "var v = String({上一步结果}); v;",
        )

        assertTrue(run.detail, run.ok)
        assertEquals("脚本返回：第一", result(run))
    }

    @Test
    fun localAndGlobalTokensBindTheirValues() {
        val run = run(
            engine(
                locals = listOf(LocalVariable("局部", "text", JsonPrimitive("局部值"))),
                globals = mapOf("全局" to JsonPrimitive("全局值")),
                meta = mapOf("全局" to GlobalVariableMeta("text")),
            ),
            "{局部} + '|' + {全局};",
        )

        assertTrue(run.detail, run.ok)
        assertEquals("局部值|全局值", result(run))
    }

    @Test
    fun getApiResolvesBareAndPrefixedNames() {
        val run = run(
            engine(
                locals = listOf(LocalVariable("局部", "text", JsonPrimitive("局部值"))),
                globals = mapOf("全局" to JsonPrimitive("全局值")),
                meta = mapOf("全局" to GlobalVariableMeta("text")),
            ),
            "get('局部') + '|' + get('全局') + '|' + get('global.全局') + '|' + get('rule.局部');",
        )

        assertTrue(run.detail, run.ok)
        assertEquals("局部值|全局值|全局值|局部值", result(run))
    }

    @Test
    fun bareTokenPrefersTheLocalAndGetCanReachTheShadowedGlobal() {
        val run = run(
            engine(
                locals = listOf(LocalVariable("shared", "text", JsonPrimitive("局部值"))),
                globals = mapOf("shared" to JsonPrimitive("全局值")),
                meta = mapOf("shared" to GlobalVariableMeta("text")),
            ),
            "{shared} + '|' + get('global.shared');",
        )

        assertTrue(run.detail, run.ok)
        assertEquals("局部值|全局值", result(run))
    }

    @Test
    fun stringBracesStayLiteral() {
        val run = run(
            engine(globals = mapOf("全局" to JsonPrimitive("全局值"))),
            "'值：{全局}';",
        )

        assertTrue(run.detail, run.ok)
        assertEquals("值：{全局}", result(run))
    }

    @Test
    fun writesAreVisibleToLaterSteps() {
        val writes = Writes()
        val run = run(
            engine(
                writes = writes,
                locals = listOf(LocalVariable("局部", "text", JsonPrimitive("原局部"))),
                globals = mapOf("全局" to JsonPrimitive("原全局")),
                meta = mapOf("全局" to GlobalVariableMeta("text")),
            ),
            "set('局部', '新局部'); set('全局', '新全局'); 'ok';",
            "String({局部}) + '|' + String({全局});",
        )

        assertTrue(run.detail, run.ok)
        assertEquals("新局部|新全局", result(run))
        assertEquals("新局部", writes.rule["局部"])
        assertEquals("新全局", writes.global["全局"])
    }

    @Test
    fun getReflectsWritesInsideTheSameScript() {
        val run = run(
            engine(
                globals = mapOf("全局" to JsonPrimitive("原全局")),
                meta = mapOf("全局" to GlobalVariableMeta("text")),
            ),
            "set('全局', '新全局'); get('全局');",
        )

        assertTrue(run.detail, run.ok)
        assertEquals("新全局", result(run))
    }

    @Test
    fun writingABuiltinFailsWithAReadableMessage() {
        val run = run(engine(), "set('上一步结果', 'x');")

        assertFalse("内置变量应只读：${run.detail}", run.ok)
        assertTrue(run.detail, run.detail.contains("只读"))
    }

    @Test
    fun typedGlobalsArriveAsJavaScriptValues() {
        val run = run(
            engine(
                globals = mapOf(
                    "全局整数" to JsonPrimitive(7),
                    "全局列表" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3))),
                ),
                meta = mapOf(
                    "全局整数" to GlobalVariableMeta("int"),
                    "全局列表" to GlobalVariableMeta("int_list"),
                ),
            ),
            "typeof {全局整数} + ':' + {全局整数} + '|' + {全局列表}.length + ':' + {全局列表}[1];",
        )

        assertTrue(run.detail, run.ok)
        assertEquals("number:7|3:2", result(run))
    }

    @Test
    fun wrongTypedWriteFailsTheAction() {
        val run = run(
            engine(
                globals = mapOf("全局整数" to JsonPrimitive(7)),
                meta = mapOf("全局整数" to GlobalVariableMeta("int")),
            ),
            "set('全局整数', '不是整数');",
        )

        assertFalse("整数变量写非整数值应失败：${run.detail}", run.ok)
        assertTrue(run.detail, run.detail.contains("不是整数"))
    }
}
