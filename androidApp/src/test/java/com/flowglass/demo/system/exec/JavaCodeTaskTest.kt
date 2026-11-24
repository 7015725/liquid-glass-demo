package com.flowglass.demo.system.exec

import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import com.flowglass.engine.ScriptVariableAccess
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JavaCodeTaskTest {
    private fun variables(): Pair<ScriptVariableAccess, MutableMap<String, Any?>> {
        val map = linkedMapOf<String, Any?>()
        val access = object : ScriptVariableAccess {
            override fun read(name: String): Any? = map[name]
            override fun write(name: String, value: Any?) { map[name] = value }
            override fun names(): Set<String> = map.keys
        }
        return access to map
    }

    private fun task(code: String, timeout: Int = 30) = CommandItem(
        id = 1,
        name = "执行 Java 代码",
        parameter = buildJsonObject {
            put("code", JsonPrimitive(code))
            put("timeout", JsonPrimitive(timeout))
        }.toString(),
        ic = Ic.Bolt,
        typeId = "action.java_code",
    )

    private fun command(item: CommandItem) = Cmd(
        id = 1,
        auto = false,
        ic = Ic.Bolt,
        title = "Java 测试",
        desc = "",
        actions = listOf(item),
    )

    @Test
    fun returnsTheLastValue() {
        val item = task("return 1 + 2;")
        val result = JavaCodeTask.run(
            RuntimeEnvironment.getApplication(), command(item), item, variables().first,
        )
        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertEquals("3", result.outputs["java.result"])
    }

    @Test
    fun readsAndWritesTaskVariables() {
        val (access, map) = variables()
        val item = task("""host.setVariable("flag", "on"); return host.getVariable("flag");""")
        val result = JavaCodeTask.run(RuntimeEnvironment.getApplication(), command(item), item, access)
        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertEquals("on", map["flag"])
        assertEquals("on", result.outputs["java.result"])
    }

    @Test
    fun pendingCodeIsNotExecuted() {
        val context = RuntimeEnvironment.getApplication()
        val code = "return 7;"
        JavaCodeGate.markPending(context, code)
        val (access, map) = variables()
        val item = task(code)
        val result = JavaCodeTask.run(context, command(item), item, access)
        assertTrue(result.detail, result.status == StepStatus.FAILURE)
        assertTrue(result.detail, result.detail.contains("待确认"))
        assertTrue(map.isEmpty())
        JavaCodeGate.confirm(context, code)
    }

    @Test
    fun syntaxErrorReportsTheLine() {
        val item = task("int x = ;")
        val result = JavaCodeTask.run(
            RuntimeEnvironment.getApplication(), command(item), item, variables().first,
        )
        assertTrue(result.detail, result.status == StepStatus.FAILURE)
        assertTrue(result.detail, result.detail.contains("第 1 行"))
    }

    @Test(timeout = 10_000)
    fun runawayLoopReportsTimeout() {
        val started = System.nanoTime()
        val item = task("while (true) { host.sleep(50); }", timeout = 1)
        val result = JavaCodeTask.run(
            RuntimeEnvironment.getApplication(), command(item), item, variables().first,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.detail, result.status == StepStatus.FAILURE)
        assertTrue(result.detail, result.detail.contains("超时"))
        assertTrue(result.detail, result.detail.contains("上限 1 秒"))
        assertTrue(result.detail, !result.detail.contains("第 1 行"))
        assertTrue("超时必须在 8 秒内返回，实际 $elapsedMs ms", elapsedMs < 8_000)
    }

    @Test
    fun seesTheAppContextAndAndroidClasses() {
        val item = task("""import android.os.Build; return context.getPackageName() + "/" + Build.MODEL;""")
        val result = JavaCodeTask.run(
            RuntimeEnvironment.getApplication(), command(item), item, variables().first,
        )
        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertTrue(result.detail, result.outputs["java.result"].toString().startsWith("com.flowglass.demo/"))
    }

    @Test
    fun secondRunReadsThePreviousWrite() {
        val (access, _) = variables()
        val first = task("""host.setVariable("shared", "v1"); return "ok";""")
        JavaCodeTask.run(RuntimeEnvironment.getApplication(), command(first), first, access)
        val second = task("""return host.getVariable("shared");""")
        val result = JavaCodeTask.run(RuntimeEnvironment.getApplication(), command(second), second, access)
        assertTrue(result.detail, result.status == StepStatus.SUCCESS)
        assertEquals("v1", result.outputs["java.result"])
    }

    @Test
    fun writesAreVisibleAfterTheRun() {
        val (access, map) = variables()
        val item = task("""host.setVariable("count", 5); return "ok";""")
        JavaCodeTask.run(RuntimeEnvironment.getApplication(), command(item), item, access)
        assertEquals(5L, map["count"])
    }
}
