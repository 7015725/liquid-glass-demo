package com.flowglass.demo.system.exec

import android.content.Context
import bsh.EvalError
import bsh.Interpreter
import bsh.TargetError
import com.flowglass.engine.ScriptVariableAccess
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Runs BeanShell inside the app process with the app's full privileges. No ClassShutter.
 * A script that never returns cannot be killed, so the timeout abandons the worker thread.
 */
internal class InProcessJavaCodeRunner(private val context: Context) : JavaCodeRunner {
    override fun run(request: JavaCodeRequest): JavaCodeResult {
        val logs = StringBuilder()
        val printed = ByteArrayOutputStream()
        val writes = linkedMapOf<String, Any?>()
        val deadline = System.nanoTime() + request.timeoutSeconds * 1_000_000_000L
        val host = JavaCodeHost(
            context, request.taskName, request.taskId,
            OverlayAccess(request.variables, writes), logs, deadline,
        )
        var value: Any? = null
        var failure: Throwable? = null

        JavaCodeCrashMarker.markRunning(context)
        try {
            val worker = Thread({
                try {
                    val interpreter = Interpreter()
                    interpreter.setClassLoader(context.classLoader)
                    interpreter.setOut(PrintStream(printed, true, "UTF-8"))
                    interpreter.setErr(PrintStream(printed, true, "UTF-8"))
                    interpreter.set("context", context)
                    interpreter.set("host", host)
                    value = interpreter.eval(request.code)
                } catch (error: Throwable) {
                    failure = error
                }
            }, "flowglass-java-code")
            worker.isDaemon = true
            worker.start()
            try {
                worker.join(request.timeoutSeconds * 1000L)
            } catch (cancelled: InterruptedException) {
                worker.interrupt()
                throw cancelled
            }
            if (worker.isAlive) {
                worker.interrupt()
                return JavaCodeResult(
                    StepStatus.FAILURE,
                    "Java 代码执行超时（上限 ${request.timeoutSeconds} 秒）；未响应的代码可能仍在后台运行，必要时请结束应用",
                    writes = snapshot(writes),
                )
            }
            failure?.let { return failureResult(it, writes) }
        } finally {
            JavaCodeCrashMarker.clear(context)
        }

        val text = value?.toString().orEmpty().take(MAX_RESULT_CHARS)
        val stdout = printed.toString("UTF-8").take(MAX_LOG_CHARS)
        val logText = logs.toString().take(MAX_LOG_CHARS)
        val detail = buildString {
            if (stdout.isNotBlank()) append(stdout.trimEnd()).append('\n')
            if (logText.isNotBlank()) append(logText.trimEnd()).append('\n')
            append(if (text.isBlank()) "Java 代码执行完成" else "返回：$text")
        }
        return JavaCodeResult(
            StepStatus.SUCCESS,
            detail.take(900),
            mapOf("java.result" to text),
            snapshot(writes),
        )
    }

    private fun failureResult(error: Throwable, writes: Map<String, Any?>): JavaCodeResult {
        val eval = findEvalError(error)
        val line = eval?.errorLineNumber ?: 0
        val message = (error as? TargetError)?.target?.message ?: eval?.message ?: error.message
        val where = if (line > 0) "第 $line 行：" else ""
        val body = message?.take(500) ?: error.javaClass.simpleName
        return JavaCodeResult(StepStatus.FAILURE, "Java 代码$where$body", writes = writes)
    }

    private fun findEvalError(error: Throwable): EvalError? {
        var current: Throwable? = error
        while (current != null) {
            if (current is EvalError) return current
            current = current.cause
        }
        return null
    }

    /** Reads fall through to the snapshot; writes stay in [writes] until the action finishes. */
    private class OverlayAccess(
        private val snapshot: Map<String, Any?>,
        private val writes: MutableMap<String, Any?>,
    ) : ScriptVariableAccess {
        override fun read(name: String): Any? = synchronized(writes) {
            if (writes.containsKey(name)) writes[name] else snapshot[name]
        }

        override fun write(name: String, value: Any?) {
            synchronized(writes) { writes[name] = value }
        }

        override fun names(): Set<String> = synchronized(writes) { snapshot.keys + writes.keys }
    }

    private fun snapshot(writes: Map<String, Any?>): Map<String, Any?> =
        synchronized(writes) { writes.toMap() }

    private companion object {
        const val MAX_RESULT_CHARS = 4_000
        const val MAX_LOG_CHARS = 4_000
    }
}
