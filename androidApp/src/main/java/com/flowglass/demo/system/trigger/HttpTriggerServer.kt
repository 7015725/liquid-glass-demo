package com.flowglass.demo.system.trigger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSource
import com.flowglass.engine.trigger.TriggerTypes
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Small loopback-first HTTP listener for the HTTP request trigger. It is deliberately not a
 * general-purpose web server: one request per connection, a 64 KiB body cap and a per-token
 * request budget keep a LAN exposure from becoming an unbounded parser.
 */
internal class HttpTriggerServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val emit: (TriggerEvent) -> Unit,
    private val rules: () -> List<TriggerRule>,
) {
    private data class Config(val port: Int, val lan: Boolean, val tokens: Set<String>)
    private class Running(
        val socket: ServerSocket,
        val job: Job,
        @Volatile var tokens: Set<String>,
    )

    private val servers = linkedMapOf<Int, Running>()
    private val rate = mutableMapOf<String, ArrayDeque<Long>>()

    fun sync() {
        val configs = linkedMapOf<Int, Config>()
        for (rule in rules()) {
            for (trigger in rule.triggers) {
                if (trigger.typeId != TriggerTypes.HTTP_REQUEST) continue
                val token = trigger.params["token"]?.toString()?.trim('"')?.trim().orEmpty()
                // An empty token is an explicit open listener; a non-empty token keeps the
                // per-rule shared-secret behaviour. Do not silently drop an open rule.
                val port = trigger.params["port"]?.toString()?.toIntOrNull()
                    ?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT
                val lan = trigger.params["bind"]?.toString()?.trim('"') == "lan"
                val current = configs[port]
                configs[port] = if (current == null) {
                    Config(port, lan, setOf(token))
                } else {
                    current.copy(lan = current.lan || lan, tokens = current.tokens + token)
                }
            }
        }

        for ((port, running) in servers.toList()) {
            val next = configs[port]
            if (next == null) {
                close(running)
                servers.remove(port)
            } else if (next.lan != running.socket.inetAddress.isAnyLocalAddress) {
                close(running)
                servers.remove(port)
                start(next)
            } else {
                running.tokens = next.tokens
            }
        }
        for ((port, config) in configs) {
            if (port !in servers) start(config)
        }
    }

    fun stop() {
        servers.values.toList().forEach(::close)
        servers.clear()
        synchronized(rate) { rate.clear() }
    }

    private fun start(config: Config) {
        if (config.lan && Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "LAN bind rejected: ACCESS_LOCAL_NETWORK not granted")
            return
        }
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(
                    InetSocketAddress(
                        if (config.lan) InetAddress.getByName("0.0.0.0")
                        else InetAddress.getByName("127.0.0.1"),
                        config.port,
                    ),
                    16,
                )
                soTimeout = ACCEPT_TIMEOUT_MS
            }
        } catch (error: Throwable) {
            Log.w(TAG, "cannot bind HTTP trigger port ${config.port}: ${error.message}")
            return
        }
        val running = Running(
            socket = socket,
            job = scope.launch(Dispatchers.IO) { acceptLoop(socket, config.port) },
            tokens = config.tokens,
        )
        servers[config.port] = running
    }

    private fun close(running: Running) {
        running.job.cancel()
        runCatching { running.socket.close() }
    }

    private fun acceptLoop(socket: ServerSocket, port: Int) {
        while (scope.isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                continue
            }
            try {
                client.use { serve(it, port) }
            } catch (error: Throwable) {
                Log.w(TAG, "HTTP request failed: ${error.message}")
            }
        }
    }

    private fun serve(client: Socket, port: Int) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val request = readRequest(input)
        if (request == null) {
            respond(output, 400, "bad request")
            return
        }
        if (request.method !in ALLOWED_METHODS) {
            respond(output, 405, "method not allowed")
            return
        }
        val running = servers[port] ?: run {
            respond(output, 503, "listener stopped")
            return
        }
        if ("" !in running.tokens && request.token !in running.tokens) {
            respond(output, 401, "unauthorized")
            return
        }
        if (!allowToken(request.token)) {
            respond(output, 429, "too many requests")
            return
        }
        emit(
            TriggerEvent(
                kind = TriggerKinds.HTTP_REQUEST,
                source = TriggerSource.HTTP,
                atEpochMs = System.currentTimeMillis(),
                data = mapOf(
                    "http.token" to request.token,
                    "http.method" to request.method,
                    "http.path" to request.path,
                    "http.query" to request.query,
                    "http.body" to request.body,
                    "http.remote" to (client.inetAddress?.hostAddress ?: ""),
                ),
            ),
        )
        respond(output, 202, "accepted")
    }

    private fun allowToken(token: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(rate) {
            val window = rate.getOrPut(token) { ArrayDeque() }
            while (window.isNotEmpty() && now - window.first() >= RATE_WINDOW_MS) window.removeFirst()
            if (window.size >= RATE_MAX) return false
            window.addLast(now)
            return true
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: String,
        val token: String,
        val body: String,
    )

    private fun readRequest(input: BufferedInputStream): Request? {
        val requestLine = readLine(input, MAX_LINE_BYTES) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val query = target.substringAfter('?', "")
        val path = target.substringBefore('?')
        if (path.length > MAX_LINE_BYTES) return null

        val headers = mutableMapOf<String, String>()
        var total = requestLine.length
        while (true) {
            val line = readLine(input, MAX_LINE_BYTES) ?: return null
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADER_BYTES) return null
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null
        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(bodyBytes, offset, contentLength - offset)
            if (read <= 0) return null
            offset += read
        }
        val body = String(bodyBytes, StandardCharsets.UTF_8)
        val headerToken = headers["x-flowglass-token"].orEmpty()
        val auth = headers["authorization"].orEmpty()
        val bearer = if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else ""
        val token = headerToken.ifEmpty { bearer }.ifEmpty { queryParam(query, "token") }
        return Request(method, path, query, token, body)
    }

    private fun readLine(input: BufferedInputStream, max: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
            if (bytes.size() > max) return null
        }
        return bytes.toString("UTF-8")
    }

    private fun queryParam(query: String, name: String): String {
        if (query.isEmpty()) return ""
        return query.split('&').firstNotNullOfOrNull { pair ->
            val key = pair.substringBefore('=')
            if (key != name) null
            else runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }.orEmpty()
    }

    private fun respond(output: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private companion object {
        const val TAG = "FlowHttpTrigger"
        const val DEFAULT_PORT = 8765
        const val ACCEPT_TIMEOUT_MS = 1_000
        const val READ_TIMEOUT_MS = 3_000
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
        const val RATE_MAX = 60
        const val RATE_WINDOW_MS = 60_000L
        val ALLOWED_METHODS = setOf("GET", "POST", "PUT")
    }
}
