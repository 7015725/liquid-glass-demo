package com.flowglass.demo.system.exec

import com.flowglass.demo.system.Root
import com.flowglass.demo.system.RootInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/**
 * One long-lived `su` shell. Commands are serialized and framed with a random request id;
 * normal commands reuse the shell, while a timeout or an output overflow closes the whole
 * session after asking the root side to terminate the active process group.
 *
 * A command that has already been sent is never replayed automatically. That is deliberate:
 * an ambiguous result (for example `am start`) may have performed its side effect.
 */
internal class RootSession(
    private val newShell: (RootInfo) -> RootShell = { SuRootShell(it) },
) {
    private val mutex = Mutex()
    private var shell: RootShell? = null
    private var shellPath: String? = null
    private var active: ActiveCommand? = null
    private var closed = false

    suspend fun execute(
        root: RootInfo,
        command: String,
        timeoutMs: Long = 30_000,
        outputLimit: Int = 500,
    ): ProcessResult {
        require(command.isNotBlank() && '\u0000' !in command)
        require(timeoutMs in 1..300_000)
        require(outputLimit in 0..BoundedProcess.MAX_OUTPUT_LIMIT)

        return mutex.withLock {
            check(!closed) { "Root 会话已关闭" }
            ensureShell(root)
            val id = "flowglass-${UUID.randomUUID()}"
            val activeCommand = ActiveCommand(id, outputLimit)
            active = activeCommand
            try {
                val target = checkNotNull(shell)
                target.request(id, command)
                val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val reader = readerScope.async { readUntilExit(target, activeCommand) }
                try {
                    val result = withTimeoutOrNull(timeoutMs) { reader.await() }
                    if (result == null) {
                        // Do not return until the root-side group is stopped and the shell is gone;
                        // otherwise a late child could keep running after the UI reports a timeout.
                        reader.cancel()
                        withContext(NonCancellable) {
                            abort(activeCommand, "命令超时，已终止 Root 会话")
                        }
                        ProcessResult(
                            -1,
                            activeCommand.output(),
                            timedOut = true,
                            truncated = activeCommand.truncated,
                            stderr = activeCommand.error(),
                        )
                    } else {
                        result
                    }
                } finally {
                    readerScope.cancel()
                }
            } catch (limit: OutputLimitExceeded) {
                withContext(NonCancellable) { abort(activeCommand, "命令输出超过上限，已终止 Root 会话") }
                ProcessResult(
                    -1,
                    activeCommand.output(),
                    timedOut = false,
                    truncated = true,
                    stderr = activeCommand.error(),
                )
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { abort(activeCommand, "执行被取消，已终止 Root 会话") }
                throw cancelled
            } catch (failure: Throwable) {
                withContext(NonCancellable) { abort(activeCommand, "Root 会话中断") }
                throw failure
            } finally {
                active = null
            }
        }
    }

    /** Closes the shell and its active child group; used on service shutdown and root revocation. */
    suspend fun close() {
        mutex.withLock {
            closed = true
            val current = active
            if (current != null) {
                withContext(NonCancellable) { abort(current, "关闭 Root 会话") }
            } else {
                destroyShell()
            }
        }
    }

    private suspend fun ensureShell(root: RootInfo) {
        val existing = shell
        if (existing != null && shellPath == root.suPath && existing.isAlive()) return
        destroyShell()
        val created = newShell(root)
        if (!created.start()) {
            runCatching { created.destroy() }
            error("Root 会话启动失败")
        }
        shell = created
        shellPath = root.suPath
    }

    private suspend fun readUntilExit(target: RootShell, activeCommand: ActiveCommand): ProcessResult {
        val begin = "__FLOWGLASS_BEGIN__:${activeCommand.id}"
        val pid = "__FLOWGLASS_PID__:${activeCommand.id}:"
        val exit = "__FLOWGLASS_EXIT__:${activeCommand.id}:"
        val error = "__FLOWGLASS_ERR__:${activeCommand.id}:"
        while (true) {
            val line = target.readLine() ?: throw IOException("Root 会话已断开")
            when {
                line == begin -> Unit
                line.startsWith(pid) -> {
                    val fields = line.removePrefix(pid).split(':')
                    val value = fields.getOrNull(0)?.toLongOrNull()
                        ?.takeIf { it in 2..Int.MAX_VALUE.toLong() }
                        ?: throw IOException("Root 会话返回无效进程组")
                    activeCommand.pid = value
                    activeCommand.grouped = fields.getOrNull(1) == "1"
                }
                line.startsWith(exit) -> {
                    val code = line.removePrefix(exit).toIntOrNull()
                        ?: throw IOException("Root 会话返回无效退出码")
                    return ProcessResult(
                        code,
                        activeCommand.output(),
                        timedOut = false,
                        truncated = activeCommand.truncated,
                        stderr = activeCommand.error(),
                    )
                }
                line.startsWith(error) -> {
                    activeCommand.appendError(line.removePrefix(error))
                    if (activeCommand.truncated) {
                        throw OutputLimitExceeded()
                    }
                }
                else -> {
                    activeCommand.append(line)
                    if (activeCommand.truncated) {
                        throw OutputLimitExceeded()
                    }
                }
            }
        }
    }

    private suspend fun abort(activeCommand: ActiveCommand, reason: String) {
        val target = shell
        val pid = activeCommand.pid
        if (target != null && pid != null) {
            runCatching { target.killGroup(pid, activeCommand.grouped) }
        }
        if (target != null) {
            runCatching { target.destroy() }
        }
        shell = null
        shellPath = null
        // Keep the reason visible in the step output even when the command produced output.
        activeCommand.append(reason)
    }

    private suspend fun destroyShell() {
        shell?.let { runCatching { it.destroy() } }
        shell = null
        shellPath = null
    }

    private class ActiveCommand(val id: String, limit: Int) {
        private val stdout = StreamBuffer(limit)
        private val stderr = StreamBuffer(limit)
        var pid: Long? = null
        var grouped = false
        val truncated: Boolean get() = stdout.truncated || stderr.truncated

        fun append(line: String) = stdout.append(line)
        fun appendError(line: String) = stderr.append(line)
        fun output(): String = stdout.value()
        fun error(): String = stderr.value()
    }

    private class StreamBuffer(private val limit: Int) {
        private val text = StringBuilder()
        var truncated = false
            private set

        fun append(line: String) {
            if (text.length >= limit) {
                truncated = true
                return
            }
            val remaining = limit - text.length
            if (line.length + 1 > remaining) {
                text.append(line.take(remaining))
                truncated = true
            } else {
                text.append(line).append('\n')
            }
        }

        fun value(): String = text.toString().trimEnd()
    }

    private class OutputLimitExceeded : IOException("Root 命令输出超过上限")
}

/** Test seam for the persistent shell; the production implementation is [SuRootShell]. */
internal interface RootShell {
    suspend fun start(): Boolean
    fun isAlive(): Boolean
    suspend fun request(id: String, command: String)
    suspend fun readLine(): String?
    fun destroy()
    suspend fun killGroup(pid: Long, grouped: Boolean)
}

/**
 * Persistent `su` shell. Each request runs in a separate `setsid` group when the ROM provides
 * `setsid`; the group id is reported before the command runs so a timeout can kill the whole
 * tree instead of only the shell wrapper. If `setsid` is unavailable, only the direct child is
 * signalled and [RootSession] still closes the shell to avoid silently reusing uncertain state.
 */
internal class SuRootShell(private val root: RootInfo) : RootShell {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        val started = ProcessBuilder(root.suPath, "-c", "exec /system/bin/sh")
            .redirectErrorStream(true)
            .start()
        process = started
        val output = started.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        writer = output
        output.write(SUPERVISOR)
        output.write("\n")
        output.flush()
        reader = started.inputStream.bufferedReader(StandardCharsets.UTF_8)
        started.isAlive
    }

    override suspend fun request(id: String, command: String) = withContext(Dispatchers.IO) {
        val output = checkNotNull(writer)
        val payload = Base64.getEncoder().encodeToString(command.toByteArray(StandardCharsets.UTF_8))
        output.write("$id:$payload\n")
        output.flush()
    }

    override fun isAlive(): Boolean = process?.isAlive == true

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        checkNotNull(reader).readLine()
    }

    override fun destroy() {
        val current = process ?: return
        process = null
        reader = null
        writer = null
        runCatching { current.outputStream.close() }
        runCatching { current.destroy() }
        if (current.isAlive) runCatching { current.destroyForcibly() }
        runCatching { current.inputStream.close() }
        runCatching { current.errorStream.close() }
    }

    override suspend fun killGroup(pid: Long, grouped: Boolean) {
        val command = if (grouped) {
            "kill -TERM -$pid 2>/dev/null; sleep 0.2; kill -KILL -$pid 2>/dev/null"
        } else {
            "kill -TERM $pid 2>/dev/null; sleep 0.2; kill -KILL $pid 2>/dev/null"
        }
        // This is a short, exceptional path only. Normal commands never start another su.
        Root.runProcess(listOf(root.suPath, "-c", command), timeoutMs = 5_000, outputLimit = 200)
    }

    private companion object {
        val SUPERVISOR = """
            PS1=
            export PS1
            while IFS= read -r line; do
              id=${'$'}{line%%:*}
              payload=${'$'}{line#*:}
              cmd=${'$'}(printf '%s' "${'$'}payload" | base64 -d 2>/dev/null)
              printf '__FLOWGLASS_BEGIN__:%s\n' "${'$'}id"
              if command -v setsid >/dev/null 2>&1; then
                setsid /system/bin/sh -c "${'$'}cmd" 2>&1 &
                grouped=1
              else
                /system/bin/sh -c "${'$'}cmd" 2>&1 &
                grouped=0
              fi
              pid=${'$'}!
              printf '\n__FLOWGLASS_PID__:%s:%s:%s\n' "${'$'}id" "${'$'}pid" "${'$'}grouped"
              wait "${'$'}pid"
              code=${'$'}?
              printf '\n__FLOWGLASS_EXIT__:%s:%s\n' "${'$'}id" "${'$'}code"
            done
        """.trimIndent()
    }
}

/** Process-wide session; one root shell is shared by all actions in the app process. */
internal object RootSessions {
    private val session = RootSession()

    suspend fun run(
        root: RootInfo,
        command: String,
        timeoutMs: Long = 30_000,
        outputLimit: Int = 500,
    ): ProcessResult = session.execute(root, command, timeoutMs, outputLimit)

    suspend fun close() = session.close()
}
