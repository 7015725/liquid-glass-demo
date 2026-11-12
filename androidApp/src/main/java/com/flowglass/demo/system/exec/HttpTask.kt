package com.flowglass.demo.system.exec

import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

internal object HttpTask {
    suspend fun run(spec: HttpRequestSpec, timeoutMs: Long = 15_000): TaskOutcome {
        val scope = CoroutineScope(currentCoroutineContext() + Dispatchers.IO)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val connection = spec.url.openConnection() as HttpURLConnection
                val worker = scope.launch {
                    try {
                        val result = request(connection, spec, timeoutMs)
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    } finally { connection.disconnect() }
                }
                continuation.invokeOnCancellation {
                    worker.cancel()
                    // Cancellation may originate on Main; closing a socket stays on IO.
                    CoroutineScope(Dispatchers.IO).launch { connection.disconnect() }
                }
            }
        } ?: TaskOutcome(StepStatus.FAILURE, "HTTP 请求超时")
    }

    private fun request(connection: HttpURLConnection, spec: HttpRequestSpec, timeoutMs: Long): TaskOutcome {
        try {
            connection.connectTimeout = timeoutMs.toInt()
            connection.readTimeout = timeoutMs.toInt()
            connection.instanceFollowRedirects = false
            connection.requestMethod = spec.method
            connection.setRequestProperty("Accept-Encoding", "identity")
            spec.body?.let { body ->
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= 1_048_576) { "参数格式不正确：期望 GET url / POST url 内容（内容最多 1 MiB）" }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.reader(StandardCharsets.UTF_8)?.use { reader ->
                val chars = CharArray(200)
                var count = 0
                while (count < chars.size) {
                    val read = reader.read(chars, count, chars.size - count)
                    if (read < 0) break
                    count += read
                }
                String(chars, 0, count)
            }.orEmpty()
            return TaskOutcome(if (status in 200..299) StepStatus.SUCCESS else StepStatus.FAILURE, "HTTP $status\n$body")
        } catch (_: SocketTimeoutException) {
            return TaskOutcome(StepStatus.FAILURE, "HTTP 请求超时")
        }
    }
}
