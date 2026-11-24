package com.flowglass.demo.system.exec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.engine.JavaScriptLanguage
import com.flowglass.engine.LanguageBinding
import com.flowglass.engine.PreparedLanguageScript
import com.flowglass.engine.ScriptVariableAccess
import kotlinx.coroutines.runBlocking
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.net.URL

/**
 * Sandboxed JavaScript action. Rhino is interpreted (no bytecode generation), starts from the
 * safe standard objects, and exposes only the small API registered below. The class shutter
 * blocks every Java class, so scripts cannot reach reflection, files or process APIs.
 */
internal object ScriptTask {
    private const val MAX_SCRIPT_CHARS = 64 * 1024
    private const val MAX_RESULT_CHARS = 4_000
    private const val MAX_LOG_CHARS = 4_000
    private const val MAX_INSTRUCTIONS = 2_000_000
    private const val MAX_STACK_DEPTH = 256
    private const val MAX_SLEEP_MS = 5_000L

    private val ALLOWED_CLASSES = emptySet<String>()

    private class ScriptTimeoutException(message: String) : RuntimeException(message)
    private class ScriptMemoryException(message: String) : RuntimeException(message)

    private class DeadlineFactory(
        private val deadlineNanos: Long,
    ) : ContextFactory() {
        override fun makeContext(): RhinoContext = super.makeContext().apply {
            optimizationLevel = -1
            languageVersion = RhinoContext.VERSION_ES6
            maximumInterpreterStackDepth = MAX_STACK_DEPTH
            instructionObserverThreshold = 10_000
            setClassShutter(ClassShutter { className -> className in ALLOWED_CLASSES })
        }

        override fun observeInstructionCount(cx: RhinoContext, instructionCount: Int) {
            if (Thread.currentThread().isInterrupted) {
                throw ScriptTimeoutException("脚本已停止")
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw ScriptTimeoutException("脚本执行超时")
            }
            if (instructionCount > MAX_INSTRUCTIONS) {
                throw ScriptMemoryException("脚本指令数超过保护上限")
            }
        }
    }

    fun run(context: Context, cmd: Cmd, task: CommandItem, variables: ScriptVariableAccess?): TaskOutcome {
        val script = scriptOf(task.parameter).trim()
        require(script.isNotEmpty()) { "参数格式不正确：脚本不能为空" }
        require(script.length <= MAX_SCRIPT_CHARS) { "脚本过长（最多 ${MAX_SCRIPT_CHARS / 1024} KiB）" }
        val timeoutSeconds = parseTimeout(task.parameter)
        val logs = StringBuilder()
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        val bindings = variables?.names().orEmpty().sorted().map { name ->
            LanguageBinding(name, variables?.read(name))
        }
        val prepared = JavaScriptLanguage.prepare(script, bindings)

        return try {
            val result = DeadlineFactory(deadline).call { cx ->
                val scope = cx.initSafeStandardObjects()
                installApi(cx, scope, context, cmd, variables, prepared, logs, deadline)
                cx.evaluateString(scope, prepared.source, "script.js", 1, null)
            }
            val text = RhinoContext.toString(result)
            val logText = logs.toString().take(MAX_LOG_CHARS)
            val output = text.take(MAX_RESULT_CHARS)
            val detail = buildString {
                if (logText.isNotBlank()) append(logText).append('\n')
                append(if (output.isBlank()) "脚本执行完成" else "脚本返回：$output")
            }
            TaskOutcome(StepStatus.SUCCESS, detail.take(900), mapOf("script.result" to output))
        } catch (timeout: ScriptTimeoutException) {
            TaskOutcome(StepStatus.FAILURE, "${timeout.message}（上限 ${timeoutSeconds} 秒）")
        } catch (memory: ScriptMemoryException) {
            TaskOutcome(StepStatus.FAILURE, memory.message ?: "脚本资源超过保护上限")
        } catch (error: EvaluatorException) {
            TaskOutcome(StepStatus.FAILURE, "脚本第 ${error.lineNumber} 行：${error.details()}")
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Throwable) {
            TaskOutcome(StepStatus.FAILURE, "脚本执行失败：${error.message?.take(500) ?: error.javaClass.simpleName}")
        }
    }

    /** The timeout parameter is parsed by ParameterCodec before execution; this keeps the
     * action usable from direct tests and hand-edited documents. */
    private fun parseTimeout(parameter: String): Long {
        val raw = parameter.timeoutOrNull() ?: 10L
        return raw.coerceIn(1L, 60L)
    }

    private fun scriptOf(parameter: String): String {
        val text = parameter.trim()
        if (!text.startsWith("{")) return text
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text).let { element ->
                (element as? kotlinx.serialization.json.JsonObject)
                    ?.get("script")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
        }.getOrNull() ?: text
    }

    private fun String.timeoutOrNull(): Long? {
        if (!trim().startsWith("{")) return null
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(this).let { element ->
                (element as? kotlinx.serialization.json.JsonObject)
                    ?.get("timeout")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
            }
        }.getOrNull()
    }

    private fun installApi(
        cx: RhinoContext,
        scope: Scriptable,
        context: Context,
        cmd: Cmd,
        variables: ScriptVariableAccess?,
        prepared: PreparedLanguageScript,
        logs: StringBuilder,
        deadline: Long,
    ) {
        val scriptable = scope as? ScriptableObject
        for ((name, value) in prepared.bindings) {
            val source = prepared.bindingSources[name]
            // `{变量}` is a read-only view, but it must not be a snapshot: after set() the next
            // read in the same script sees the new value. Rhino getter slots give us that while
            // still rejecting assignment from script code.
            if (scriptable != null && source != null && variables != null) {
                val getter = ScriptableObject.LambdaGetterFunction { _ ->
                    jsValue(cx, scope, variables.read(source))
                }
                scriptable.defineProperty(cx, name, getter, ScriptableObject.READONLY)
            } else {
                ScriptableObject.putProperty(scope, name, jsValue(cx, scope, value))
                scriptable?.setAttributes(name, ScriptableObject.READONLY)
            }
        }
        fun put(name: String, fn: (Array<Any?>) -> Any?) {
            ScriptableObject.putProperty(scope, name, object : BaseFunction() {
                override fun getFunctionName(): String = name
                override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? =
                    fn(args)
            })
        }

        put("log") { args ->
            val line = args.joinToString(" ") { RhinoContext.toString(it) }
            if (logs.length < MAX_LOG_CHARS) logs.append(line).append('\n')
            null
        }
        put("get") { args -> readVariable(cx, scope, variables, RhinoContext.toString(args.firstOrNull())) }
        put("set") { args ->
            val name = RhinoContext.toString(args.firstOrNull())
            val value = args.getOrNull(1)
            variables?.write(name, fromJsValue(value))
            value
        }
        put("toast") { args ->
            val text = RhinoContext.toString(args.firstOrNull()).take(500)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
            null
        }
        put("notify") { args ->
            val title = RhinoContext.toString(args.firstOrNull()).ifBlank { cmd.title }.take(80)
            val text = RhinoContext.toString(args.getOrNull(1)).take(500)
            notify(context, title, text)
            null
        }
        put("http") { args ->
            val method = RhinoContext.toString(args.firstOrNull()).uppercase()
            val url = RhinoContext.toString(args.getOrNull(1))
            val body = args.getOrNull(2)?.let { RhinoContext.toString(it) }
            http(method, url, body, deadline)
        }
        put("sleep") { args ->
            val millis = (args.firstOrNull()?.let { org.mozilla.javascript.ScriptRuntime.toNumber(it) } ?: 0.0)
                .toLong().coerceIn(0L, MAX_SLEEP_MS)
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) throw ScriptTimeoutException("脚本执行超时")
            Thread.sleep(millis.coerceAtMost(remaining))
            null
        }
    }

    private fun readVariable(
        cx: RhinoContext,
        scope: Scriptable,
        variables: ScriptVariableAccess?,
        name: String,
    ): Any? {
        if (variables == null) return null
        return jsValue(cx, scope, variables.read(name))
    }

    private fun jsValue(cx: RhinoContext, scope: Scriptable, value: Any?): Any? = when (value) {
        null -> null
        is Boolean, is String -> value
        is Double -> if (value == value.toLong().toDouble()) value.toLong() else value
        is Float -> if (value == value.toLong().toFloat()) value.toLong() else value
        is Number -> value
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> when {
            value.isString -> value.content
            value.content == "true" -> true
            value.content == "false" -> false
            else -> value.content.toLongOrNull() ?: value.content
        }
        is kotlinx.serialization.json.JsonArray ->
            cx.newArray(scope, value.map { jsValue(cx, scope, it) }.toTypedArray())
        is List<*> -> cx.newArray(scope, value.map { jsValue(cx, scope, it) }.toTypedArray())
        is Array<*> -> cx.newArray(scope, value.map { jsValue(cx, scope, it) }.toTypedArray())
        else -> value.toString()
    }

    private fun fromJsValue(value: Any?): Any? = when (value) {
        is NativeArray -> (0 until value.length.toInt()).map { fromJsValue(value.get(it, value)) }
        is Array<*> -> value.map(::fromJsValue)
        is Double -> if (value == value.toLong().toDouble()) value.toLong() else value
        is Float -> if (value == value.toLong().toFloat()) value.toLong() else value
        is CharSequence -> value.toString()
        else -> value
    }

    private fun http(method: String, rawUrl: String, body: String?, deadline: Long): String {
        require(method == "GET" || method == "POST") { "http 仅支持 GET / POST" }
        val url = try { URL(rawUrl) } catch (_: Exception) { throw IllegalArgumentException("http URL 无效") }
        require(url.protocol == "http" || url.protocol == "https") { "http 仅支持 http(s)" }
        require(url.host.isNotBlank()) { "http URL 缺少主机名" }
        val spec = HttpRequestSpec(method, url, if (method == "GET") null else body)
        // The script budget wins over the per-request cap: Rhino cannot observe instructions while
        // this blocking call runs, so the remaining script deadline has to bound the request itself.
        val remainingMs = (deadline - System.nanoTime()) / 1_000_000
        if (remainingMs <= 0) throw ScriptTimeoutException("脚本执行超时")
        val budget = remainingMs.coerceAtMost(8_000)
        val outcome = runBlocking { HttpTask.run(spec, timeoutMs = budget) }
        if (outcome.status != StepStatus.SUCCESS) {
            // Only the exact HTTP timeout sentinel may be re-attributed; a 4xx/5xx body can contain 超时 too.
            if (budget < 8_000 && outcome.detail == "HTTP 请求超时") throw ScriptTimeoutException("脚本执行超时")
            throw IllegalStateException(outcome.detail)
        }
        if (System.nanoTime() >= deadline) throw ScriptTimeoutException("脚本执行超时")
        return outcome.outputs["http.body"]?.toString().orEmpty()
    }

    private fun notify(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("缺少通知权限")
        }
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("通知服务不可用")
        val channel = "command_notifications"
        manager.createNotificationChannel(
            NotificationChannel(channel, "指令通知", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify("script:${System.nanoTime()}", 0, notification)
    }
}
