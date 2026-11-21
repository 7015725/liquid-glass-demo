package com.flowglass.demo.system.exec

import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import com.flowglass.engine.ScriptVariableAccess
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * JS 的 http() 必须把响应正文交回脚本，并且脚本的超时要覆盖阻塞中的 HTTP 调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptHttpBridgeTest {
    private lateinit var server: HttpServer

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/body") { exchange ->
            val body = "audit-ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/slow") { exchange ->
            Thread.sleep(2500)
            val body = "late".toByteArray()
            runCatching {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun task(script: String, timeout: Int) = CommandItem(
        id = 1,
        name = "执行脚本",
        parameter = buildJsonObject {
            put("script", JsonPrimitive(script))
            put("timeout", JsonPrimitive(timeout))
        }.toString(),
        ic = Ic.Bolt,
        typeId = "action.script",
    )

    private fun run(script: String, timeout: Int = 10): TaskOutcome {
        val item = task(script, timeout)
        val command = Cmd(
            id = 1, auto = false, ic = Ic.Bolt, title = "脚本测试", desc = "", actions = listOf(item),
        )
        val access = object : ScriptVariableAccess {
            override fun read(name: String): Any? = null
            override fun write(name: String, value: Any?) = Unit
            override fun names(): Set<String> = emptySet()
        }
        return ScriptTask.run(RuntimeEnvironment.getApplication(), command, item, access)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun httpReturnsTheResponseBodyToTheScript() {
        val outcome = run("var body = http('GET', '${url("/body")}'); body;")

        assertEquals(outcome.detail, StepStatus.SUCCESS, outcome.status)
        assertEquals("audit-ok", outcome.outputs["script.result"])
        assertTrue(outcome.detail, outcome.detail.contains("audit-ok"))
    }

    @Test
    fun scriptTimeoutCoversABlockingHttpCall() {
        val started = System.nanoTime()
        val outcome = run("http('GET', '${url("/slow")}'); 'finished';", timeout = 1)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(outcome.detail, StepStatus.FAILURE, outcome.status)
        assertTrue("脚本应在配置的 1 秒上限附近结束，实际 ${elapsedMs}ms", elapsedMs < 2_000)
        assertTrue("必须说明超时：${outcome.detail}", outcome.detail.contains("超时"))
    }
}
