package com.flowglass.demo.system.exec

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.flowglass.engine.ScriptVariableAccess
import kotlinx.coroutines.runBlocking
import java.net.URL

/**
 * Object exposed to Java Code scripts as `host`.
 *
 * Public, Java-friendly signatures only (no default arguments, no inline functions), because
 * BeanShell resolves these methods reflectively. There is no sandbox: the object hands the
 * script the real app Context, variables and HTTP helper.
 */
class JavaCodeHost(
    private val context: Context,
    private val taskName: String,
    private val taskId: Long,
    private val variables: ScriptVariableAccess,
    private val logs: StringBuilder,
    private val deadlineNanos: Long,
) {
    fun getVariable(name: String): Any? = variables.read(name)

    fun setVariable(name: String, value: Any?) {
        variables.write(name, normalize(value))
    }

    fun clearVariable(name: String) {
        variables.write(name, null)
    }

    fun log(message: Any?) {
        if (logs.length < MAX_LOG_CHARS) logs.append(message?.toString().orEmpty()).append('\n')
    }

    fun showToast(text: String) {
        val body = text.take(500)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, body, Toast.LENGTH_SHORT).show()
        }
    }

    fun notify(title: String, text: String) {
        CommandNotifications.post(context, title.take(80), text.take(500))
    }

    fun http(method: String, url: String): String = http(method, url, null)

    fun http(method: String, url: String, body: String?): String {
        val upper = method.uppercase()
        require(upper == "GET" || upper == "POST") { "http 仅支持 GET / POST" }
        val parsed = try {
            URL(url)
        } catch (_: Exception) {
            throw IllegalArgumentException("http URL 无效")
        }
        require(parsed.protocol == "http" || parsed.protocol == "https") { "http 仅支持 http(s)" }
        val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
        require(remainingMs > 0) { "Java 代码执行超时" }
        val outcome = runBlocking {
            HttpTask.run(
                HttpRequestSpec(upper, parsed, if (upper == "GET") null else body),
                timeoutMs = remainingMs.coerceAtMost(8_000),
            )
        }
        check(outcome.status == StepStatus.SUCCESS) { outcome.detail }
        return outcome.outputs["http.body"]?.toString().orEmpty()
    }

    fun sleep(millis: Any?) {
        val requested = (millis as? Number)?.toLong() ?: 0L
        val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000
        require(remaining > 0) { "Java 代码执行超时" }
        Thread.sleep(requested.coerceIn(0L, MAX_SLEEP_MS).coerceAtMost(remaining))
    }

    fun getTaskName(): String = taskName

    fun getTaskId(): Long = taskId

    private fun normalize(value: Any?): Any? = when {
        value == null -> null
        value == bsh.Primitive.NULL || value == bsh.Primitive.VOID -> null
        value is bsh.Primitive -> normalize(value.value)
        value is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong() else value.toDouble()
        else -> value
    }

    private companion object {
        const val MAX_LOG_CHARS = 4_000
        const val MAX_SLEEP_MS = 5_000L
    }
}
