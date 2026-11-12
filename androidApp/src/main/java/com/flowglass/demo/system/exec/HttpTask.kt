package com.flowglass.demo.system.exec

import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

internal object HttpTask {
    suspend fun run(spec: HttpRequestSpec, timeoutMs: Long = 15_000): TaskOutcome {
        val startedAt = System.nanoTime()
        val scope = CoroutineScope(currentCoroutineContext() + Dispatchers.IO)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val active = AtomicReference<HttpURLConnection?>()
                val worker = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val result = request(spec, timeoutMs, startedAt, active)
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    } finally { active.getAndSet(null)?.disconnect() }
                }
                continuation.invokeOnCancellation {
                    worker.cancel()
                    // Cancellation may originate on Main; close the current hop on IO.
                    CoroutineScope(Dispatchers.IO).launch { active.getAndSet(null)?.disconnect() }
                }
                worker.start()
            }
        } ?: TaskOutcome(StepStatus.FAILURE, "HTTP 请求超时")
    }

    private suspend fun request(
        spec: HttpRequestSpec, timeoutMs: Long, startedAt: Long, active: AtomicReference<HttpURLConnection?>,
    ): TaskOutcome {
        var url = spec.url
        var method = spec.method
        var requestBody = spec.body
        var redirects = 0
        val visited = mutableSetOf<String>()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                // URL.equals/hashCode may resolve DNS. A fragment is not sent to the server.
                val key = "$method ${url.toURI().normalize().toString().substringBefore('#')}"
                if (!visited.add(key)) return TaskOutcome(StepStatus.FAILURE, "HTTP 重定向循环")
                val connection = url.openConnection() as HttpURLConnection
                active.set(connection)
                try {
                    currentCoroutineContext().ensureActive()
                    val remaining = timeoutMs - (System.nanoTime() - startedAt) / 1_000_000
                    if (remaining <= 0) throw SocketTimeoutException()
                    connection.connectTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    connection.readTimeout = connection.connectTimeout
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = method
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    requestBody?.let { body ->
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        require(bytes.size <= 1_048_576) { "参数格式不正确：期望 GET url / POST url 内容（内容最多 1 MiB）" }
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        if (redirects == 5) return TaskOutcome(StepStatus.FAILURE, "HTTP 重定向超过 5 次")
                        redirects++
                        val location = connection.getHeaderField("Location")?.trim()
                        if (location.isNullOrEmpty()) return TaskOutcome(StepStatus.FAILURE, "HTTP 重定向缺少 Location")
                        val next = try { URL(url, location).also { it.toURI() } } catch (_: Exception) {
                            return TaskOutcome(StepStatus.FAILURE, "HTTP 重定向地址无效")
                        }
                        if (next.protocol !in listOf("http", "https") || next.host.isBlank()) {
                            return TaskOutcome(StepStatus.FAILURE, "HTTP 重定向仅支持有效的 http(s) 地址")
                        }
                        url = next
                        if (method == "POST" && status in listOf(301, 302, 303)) {
                            method = "GET"
                            requestBody = null
                        }
                        continue
                    }
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.reader(StandardCharsets.UTF_8)?.use { reader ->
                        val chars = CharArray(200)
                        var count = 0
                        while (count < chars.size) {
                            currentCoroutineContext().ensureActive()
                            val read = reader.read(chars, count, chars.size - count)
                            if (read < 0) break
                            count += read
                        }
                        String(chars, 0, count)
                    }.orEmpty()
                    return TaskOutcome(if (status in 200..299) StepStatus.SUCCESS else StepStatus.FAILURE, "HTTP $status\n$body")
                } finally {
                    active.compareAndSet(connection, null)
                    connection.disconnect()
                }
            }
        } catch (_: SocketTimeoutException) {
            return TaskOutcome(StepStatus.FAILURE, "HTTP 请求超时")
        }
    }
}
