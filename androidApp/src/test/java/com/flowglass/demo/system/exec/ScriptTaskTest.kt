package com.flowglass.demo.system.exec

import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import com.flowglass.engine.ScriptVariableAccess
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptTaskTest {
    private fun variables(): Pair<ScriptVariableAccess, MutableMap<String, Any?>> {
        val map = linkedMapOf<String, Any?>()
        return object : ScriptVariableAccess {
            override fun read(name: String): Any? = map[name]
            override fun write(name: String, value: Any?) { map[name] = value }
            override fun names(): Set<String> = map.keys
        } to map
    }

    private fun task(script: String, timeout: Int = 10) = CommandItem(
        id = 1,
        name = "执行脚本",
        parameter = buildJsonObject {
            put("script", JsonPrimitive(script))
            put("timeout", JsonPrimitive(timeout))
        }.toString(),
        ic = Ic.Bolt,
        typeId = "action.script",
    )

    private fun command(item: CommandItem) = Cmd(
        id = 1,
        auto = false,
        ic = Ic.Bolt,
        title = "脚本测试",
        desc = "",
        actions = listOf(item),
    )

    @Test
    fun readsAndWritesRunAndGlobalVariables() {
        val (access, map) = variables()
        val result = ScriptTask.run(
            RuntimeEnvironment.getApplication(),
            command(task("set('count', Number(get('count') || 0) + 1); set('last', 'ok'); get('count') + '-done';")),
            task("set('count', Number(get('count') || 0) + 1); set('last', 'ok'); get('count') + '-done';"),
            access,
        )

        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertEquals(1L, map["count"])
        assertEquals("ok", map["last"])
        assertEquals("1-done", result.outputs["script.result"])
    }

    @Test
    fun infiniteScriptStopsAtTheConfiguredTimeout() {
        val started = System.nanoTime()
        val result = ScriptTask.run(
            RuntimeEnvironment.getApplication(),
            command(task("while (true) {}", timeout = 1)),
            task("while (true) {}", timeout = 1),
            null,
        )

        assertFalse(result.detail, result.status == StepStatus.SUCCESS)
        assertTrue(result.detail, result.detail.contains("超时"))
        assertTrue("timeout took too long", System.nanoTime() - started < 4_000_000_000L)
    }

    @Test
    fun javaClassesAndReflectionAreBlocked() {
        val result = ScriptTask.run(
            RuntimeEnvironment.getApplication(),
            command(task("var c = this.getClass().getName(); c;")),
            task("var c = this.getClass().getName(); c;"),
            null,
        )

        assertFalse(result.detail, result.status == StepStatus.SUCCESS)
        assertTrue(result.detail, result.detail.contains("脚本"))
    }

    @Test
    fun newSyntaxBindsAChineseNameAsDataAndNeverExecutesIt() {
        val (access, map) = variables()
        val dangerous = "a'b\"c$(touch /tmp/pwned)`id`"
        map["名字"] = dangerous
        val result = ScriptTask.run(
            RuntimeEnvironment.getApplication(),
            command(task("var value = {名字}; value;")),
            task("var value = {名字}; value;"),
            access,
        )

        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertEquals(dangerous, result.outputs["script.result"])
    }

    @Test
    fun injectedBindingsAreReadOnly() {
        val (access, map) = variables()
        map["名字"] = "original"
        val result = ScriptTask.run(
            RuntimeEnvironment.getApplication(),
            command(task("var before = {名字}; {名字} = 'changed'; before;")),
            task("var before = {名字}; {名字} = 'changed'; before;"),
            access,
        )

        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertEquals("original", result.outputs["script.result"])
        assertEquals("original", map["名字"])
    }

    @Test
    fun jsonBracesAreNotMistakenForVariables() {
        val result = ScriptTask.run(
            RuntimeEnvironment.getApplication(),
            command(task("var obj = {a: 1}; obj.a;")),
            task("var obj = {a: 1}; obj.a;"),
            null,
        )

        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertEquals("1", result.outputs["script.result"])
    }

    @Test
    fun listsRoundTripAsJavaScriptArrays() {
        val (access, map) = variables()
        map["列表"] = listOf(1L, 2L, 3L)
        val result = ScriptTask.run(
            RuntimeEnvironment.getApplication(),
            command(task("var a = get('列表'); set('复制', a); a.length + ':' + a[1];")),
            task("var a = get('列表'); set('复制', a); a.length + ':' + a[1];"),
            access,
        )

        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertEquals("3:2", result.outputs["script.result"])
        assertEquals(listOf(1L, 2L, 3L), map["复制"])
    }

    @Test
    fun setAndGetUseChineseNames() {
        val (access, map) = variables()
        map["名字"] = "小明"
        val result = ScriptTask.run(
            RuntimeEnvironment.getApplication(),
            command(task("set('结果', get('名字') + '！'); get('结果');")),
            task("set('结果', get('名字') + '！'); get('结果');"),
            access,
        )

        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertEquals("小明！", result.outputs["script.result"])
        assertEquals("小明！", map["结果"])
    }

    @Test
    fun executionEngineRunsScriptThroughTheRealParameterCodec() = kotlinx.coroutines.runBlocking {
        val item = CommandItem(2, "执行脚本", "log('engine'); 7;", Ic.Bolt, "action.script")
        val result = ExecutionEngine(RuntimeEnvironment.getApplication()).run(command(item), "测试")

        assertTrue(result.detail, result.ok)
        assertTrue(result.detail, result.detail.contains("7"))
    }

    @Test
    fun scriptExceptionReturnsAReadableMessage() {
        val result = ScriptTask.run(
            RuntimeEnvironment.getApplication(),
            command(task("throw new Error('boom');")),
            task("throw new Error('boom');"),
            null,
        )

        assertFalse(result.detail, result.status == StepStatus.SUCCESS)
        assertTrue(result.detail, result.detail.contains("boom"))
    }
}
