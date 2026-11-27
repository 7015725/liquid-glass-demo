package com.flowglass.demo.system.trigger

import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerTypes
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpTriggerServerTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun rule(port: Int, token: String): TriggerRule {
        val trigger = TriggerComponent(
            typeId = TriggerTypes.HTTP_REQUEST,
            name = "http",
            params = mapOf(
                "token" to JsonPrimitive(token),
                "port" to JsonPrimitive(port),
                "method" to JsonPrimitive("any"),
                "path" to JsonPrimitive("/hook"),
                "bind" to JsonPrimitive("loopback"),
            ),
        )
        return TriggerRule(
            id = "http-rule",
            cmdId = 1L,
            name = "HTTP",
            enabled = true,
            triggers = listOf(trigger),
        )
    }

    private fun request(port: Int, token: String, body: String = ""): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            val payload = "POST /hook?x=1 HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "X-Flowglass-Token: $token\r\n" +
                "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n$body"
            socket.getOutputStream().write(payload.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    @Test
    fun tokenIsRequiredAndPayloadIsForwarded() {
        val port = freePort()
        val events = CopyOnWriteArrayList<TriggerEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = HttpTriggerServer(
            context = RuntimeEnvironment.getApplication(),
            scope = scope,
            emit = { events += it },
            rules = { listOf(rule(port, "hook-token")) },
        )
        try {
            server.sync()
            val rejected = request(port, "wrong", "nope")
            assertTrue(rejected.startsWith("HTTP/1.1 401"))
            assertTrue(events.isEmpty())

            val accepted = request(port, "hook-token", "hello")
            assertTrue(accepted.startsWith("HTTP/1.1 202"))
            val deadline = System.currentTimeMillis() + 2_000
            while (events.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(1, events.size)
            assertEquals("hook-token", events.single().data["http.token"])
            assertEquals("POST", events.single().data["http.method"])
            assertEquals("/hook", events.single().data["http.path"])
            assertEquals("x=1", events.single().data["http.query"])
            assertEquals("hello", events.single().data["http.body"])
        } finally {
            server.stop()
            scope.cancel()
        }
    }
}
