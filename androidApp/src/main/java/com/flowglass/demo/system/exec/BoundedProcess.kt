package com.flowglass.demo.system.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class RootPermissionDeniedException : IOException("缺少 Root 权限：su 拒绝执行")

internal data class ProcessResult(val exitCode: Int, val output: String, val timedOut: Boolean)
internal object BoundedProcess {
    private val android = File("/system/bin/sh").isFile
    private val shell = if (android) "/system/bin/sh" else "/bin/sh"
    private val setsid = if (android) "/system/bin/setsid" else "/usr/bin/setsid"
    private val sleep = if (android) "/system/bin/sleep" else "/bin/sleep"
    private val grep = if (android) "/system/bin/grep" else "/bin/grep"

    suspend fun run(arguments: List<String>, timeoutMs: Long = 30_000, outputLimit: Int = 500): ProcessResult =
        supervised(arguments, null, timeoutMs, outputLimit)

    /** The guardian is executed by su, not around the unprivileged su client. */
    suspend fun runRoot(su: String, command: String, timeoutMs: Long = 30_000): ProcessResult =
        supervised(listOf(shell, "-c", command), su, timeoutMs, 500)

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private suspend fun supervised(arguments: List<String>, su: String?, timeoutMs: Long, outputLimit: Int): ProcessResult = withContext(Dispatchers.IO) {
        require(arguments.isNotEmpty() && arguments.none { '\u0000' in it })
        require(timeoutMs in 1..300_000 && outputLimit in 0..500)
        val token = UUID.randomUUID().toString()
        // Executable discovery happens inside su: app-side visibility is not enough.
        val bootstrap = "if [ -x \"\$1\" ]; then exec \"\$@\"; else shift; exec \"\$@\"; fi"
        val launch = listOf(shell, "-c", bootstrap, "flowglass-launch", setsid, shell, "-c", guardian, "flowglass-guardian", token,
            ((timeoutMs + 9) / 10).toString(), setsid, shell, sleep, grep, worker) + arguments
        // Backgrounding prevents setsid's group-leader fork from making su return early.
        val command = "exec 3<&0; " + launch.joinToString(" ", transform = ::quote) + " <&3 3<&- & wait \$!"
        val transport = if (su == null) listOf(shell, "-c", command) else listOf(su, "-c", command)
        val started = System.nanoTime()
        val process = ProcessBuilder(transport).redirectErrorStream(true).start()
        val capture = Capture(token, outputLimit)
        val buffer = ByteArray(4096)
        var timedOut = false
        var released = false
        try {
            while (!capture.done) {
                currentCoroutineContext().ensureActive()
                drain(process, capture, buffer)
                if (capture.ready && !released) {
                    currentCoroutineContext().ensureActive()
                    process.outputStream.write("GO\n".toByteArray(StandardCharsets.US_ASCII))
                    process.outputStream.flush()
                    released = true
                }
                if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                    timedOut = true
                    break
                }
                if (!process.isAlive && process.inputStream.available() == 0) break
                if (process.inputStream.available() == 0) delay(5)
            }
        } finally {
            // EOF is the stop request. Keep reading until the execution-side guardian
            // confirms group cleanup; cancellation must not cancel this handshake.
            withContext(NonCancellable) {
                try { process.outputStream.close() } catch (_: IOException) { }
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
                while ((!capture.done || process.isAlive) && System.nanoTime() < deadline) {
                    drain(process, capture, buffer)
                    delay(5)
                }
                drain(process, capture, buffer)
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                for (stream in listOf(process.inputStream, process.errorStream, process.outputStream)) {
                    try { stream.close() } catch (_: IOException) { }
                }
                if ((released || capture.groupId != null) && !capture.done) throw IOException("执行侧未确认进程组清理；停止状态未知")
            }
        }
        if (!timedOut && (!capture.done || capture.exitCode == null)) {
            // Only a rejection before the execution-side handshake is a su
            // permission failure; identical text from a user command is not.
            val rejection = capture.output().lineSequence().map { it.trim().lowercase(java.util.Locale.ROOT) }
                .any { it == "permission denied" || it == "access denied" ||
                    (it.startsWith("su:") && listOf("permission denied", "access denied", "not allowed").any(it::contains)) }
            if (su != null && !released && rejection) throw RootPermissionDeniedException()
            throw IOException("安全进程监督不可用：需要 ps、/proc、shell 信号与控制管道")
        }
        ProcessResult(if (timedOut) -1 else checkNotNull(capture.exitCode), capture.output(), timedOut)
    }

    private fun drain(process: Process, capture: Capture, buffer: ByteArray) {
        val input = process.inputStream
        val available = input.available()
        if (available > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, available))
            for (i in 0 until count) capture.accept(buffer[i])
        }
    }

    /** Control frames are bounded and separate from the 500-character output budget. */
    private class Capture(token: String, private val limit: Int) {
        private val prefix = ("\u001e$token:").toByteArray(StandardCharsets.US_ASCII)
        private val pending = ByteArrayOutputStream(128)
        private val bytes = ByteArrayOutputStream(limit * 4 + 4)
        var ready = false
        var groupId: Long? = null
        var done = false
        var exitCode: Int? = null
        private fun retain(value: Byte) { if (bytes.size() < limit * 4 + 4) bytes.write(value.toInt()) }
        fun accept(value: Byte) {
            if (done) return
            val size = pending.size()
            if (size < prefix.size) {
                if (value == prefix[size]) pending.write(value.toInt())
                else {
                    pending.toByteArray().forEach(::retain)
                    pending.reset()
                    if (value == prefix[0]) pending.write(value.toInt()) else retain(value)
                }
            } else if (value == '\n'.code.toByte()) {
                val frame = pending.toString(StandardCharsets.US_ASCII.name()).substring(prefix.size)
                when {
                    frame.startsWith("FG_PGID:") || frame.startsWith("FG_PID:") -> {
                        groupId = frame.substringAfter(':').toLongOrNull()
                            ?.takeIf { it in 2..Int.MAX_VALUE.toLong() }
                            ?: throw IOException("无效的执行侧进程组 ID")
                    }
                    frame == "READY" -> { checkNotNull(groupId); ready = true }
                    frame == "DONE" -> done = true
                    frame.startsWith("EXIT ") -> exitCode = frame.substring(5).toIntOrNull()
                    else -> throw IOException("无效的进程监督响应")
                }
                pending.reset()
            } else {
                if (pending.size() >= 128) throw IOException("进程监督响应过长")
                pending.write(value.toInt())
            }
        }
        fun output(): String = bytes.toString(StandardCharsets.UTF_8.name()).take(limit)
    }

    // The command cannot read the control pipe. It stops before executing user
    // argv, and keeps its group ID reserved until the guardian performs one kill.
    private val worker = """
        token=${'$'}1; shift
        FLOWGLASS_EXECUTION_TOKEN=${'$'}token
        export FLOWGLASS_EXECUTION_TOKEN
        if [ "${'$'}FG_ISOLATED" = 1 ]; then
            printf '\036%s:FG_PGID:%s\n' "${'$'}token" "${'$'}${'$'}"
        else
            printf '\036%s:FG_PID:%s\n' "${'$'}token" "${'$'}${'$'}"
        fi
        unset FG_ISOLATED
        kill -STOP ${'$'}${'$'} || exit 125
        "${'$'}@" </dev/null
        code=${'$'}?
        printf '\036%s:EXIT %s\n' "${'$'}token" "${'$'}code"
        kill -STOP ${'$'}${'$'}
        exit 125
    """.trimIndent()

    // No ProcessHandle, pid enumeration from Java, external kill command or
    // shared app process group. All privileged signals originate in this shell.
    private val guardian = """
        set +m
        token=${'$'}1; ticks=${'$'}2; setsid=${'$'}3; shell=${'$'}4; sleeper=${'$'}5; grep=${'$'}6; body=${'$'}7
        shift 7
        stop=0; group=0
        trap 'stop=1' USR1 HUP INT TERM
        stat() {
            IFS= read -r line < "/proc/${'$'}1/stat" || return 1
            fields=${'$'}{line##*) }
            set -- ${'$'}fields
            state=${'$'}1; ppid=${'$'}2; pgid=${'$'}3; sid=${'$'}4
            shift 19; born=${'$'}1
        }
        list_processes() {
            snapshot=$(ps -A -o pid,ppid) || return 1
            listed=0
            while read -r child parent rest; do
                [ "${'$'}child" = "${'$'}1" ] && listed=1
            done <<FG_LIST
        ${'$'}snapshot
        FG_LIST
            [ "${'$'}listed" = 1 ]
        }
        # A zero exit with an empty/incompatible ps output is not a capability.
        list_processes "${'$'}${'$'}" || exit 125
        [ -x "${'$'}grep" ] || exit 125
        stat ${'$'}${'$'} || exit 125
        own_group=0
        if [ "${'$'}pgid" = "${'$'}${'$'}" ] && [ "${'$'}sid" = "${'$'}${'$'}" ]; then own_group=1; fi
        "${'$'}sleeper" 0.001 || exit 125
        exec 3<&0
        isolated=0
        if [ -x "${'$'}setsid" ]; then isolated=1; fi
        FG_ISOLATED=${'$'}isolated; export FG_ISOLATED
        if [ "${'$'}isolated" = 1 ]; then
            FLOWGLASS_EXECUTION_TOKEN=${'$'}token "${'$'}setsid" "${'$'}shell" -c "${'$'}body" flowglass-worker "${'$'}token" "${'$'}@" 3<&- </dev/null &
        else
            FLOWGLASS_EXECUTION_TOKEN=${'$'}token "${'$'}shell" -c "${'$'}body" flowglass-worker "${'$'}token" "${'$'}@" 3<&- </dev/null &
        fi
        group=${'$'}!
        n=0
        while [ "${'$'}n" -lt 100 ]; do
            stat "${'$'}group" || break
            [ "${'$'}state" = T ] && break
            [ "${'$'}state" = Z ] && break
            "${'$'}sleeper" 0.01 3<&- </dev/null || break
            n=${'$'}((n+1))
        done
        if [ "${'$'}state" != T ] || { [ "${'$'}isolated" = 1 ] && { [ "${'$'}pgid" != "${'$'}group" ] || [ "${'$'}sid" != "${'$'}group" ]; }; }; then
            kill -KILL "${'$'}group" 2>/dev/null
            wait "${'$'}group"
            exit 125
        fi
        if [ "${'$'}isolated" = 1 ]; then kill -0 "-${'$'}group" || exit 125; fi
        if ! "${'$'}grep" -z -F -x "FLOWGLASS_EXECUTION_TOKEN=${'$'}token" "/proc/${'$'}group/environ" >/dev/null 2>&1; then
            kill -KILL "${'$'}group" 2>/dev/null
            wait "${'$'}group" 2>/dev/null
            exit 125
        fi
        freeze_tree() {
            queue=${'$'}1; victims=; seen=" "
            while [ -n "${'$'}queue" ]; do
                node=${'$'}{queue%% *}
                if [ "${'$'}queue" = "${'$'}node" ]; then queue=; else queue=${'$'}{queue#* }; fi
                case "${'$'}node" in ''|*[!0-9]*) return 1;; esac
                [ "${'$'}node" -gt 1 ] || return 1
                case "${'$'}seen" in *" ${'$'}node "*) continue;; esac
                seen="${'$'}seen${'$'}node "
                stat "${'$'}node" 2>/dev/null || continue
                identity=${'$'}born
                if [ "${'$'}state" != Z ] && [ "${'$'}state" != X ]; then
                    kill -STOP "${'$'}node" 2>/dev/null || return 1
                    tries=0
                    while [ "${'$'}state" != T ] && [ "${'$'}state" != Z ] && [ "${'$'}state" != X ]; do
                        [ "${'$'}tries" -lt 100 ] || return 1
                        "${'$'}sleeper" 0.001 3<&- </dev/null
                        stat "${'$'}node" 2>/dev/null || return 1
                        [ "${'$'}born" = "${'$'}identity" ] || return 1
                        tries=${'$'}((tries+1))
                    done
                fi
                victims="${'$'}node:${'$'}identity ${'$'}victims"
                list_processes "${'$'}node" || return 1
                while read -r child parent rest; do
                    [ "${'$'}parent" = "${'$'}node" ] || continue
                    case "${'$'}child" in ''|*[!0-9]*) return 1;; esac
                    queue="${'$'}{queue:+${'$'}queue }${'$'}child"
                done <<FG_PS
        ${'$'}snapshot
        FG_PS
            done
        }
        kill_tree() {
            protected=${'$'}{1:-0}
            # Breadth-first discovery prepends IDs, giving leaf-to-root order.
            for entry in ${'$'}victims; do
                victim=${'$'}{entry%%:*}; identity=${'$'}{entry#*:}
                [ "${'$'}victim" = "${'$'}protected" ] && continue
                stat "${'$'}victim" 2>/dev/null || continue
                [ "${'$'}born" = "${'$'}identity" ] || return 1
                [ "${'$'}state" = Z ] || [ "${'$'}state" = X ] || kill -KILL "${'$'}victim" || return 1
            done
            for entry in ${'$'}victims; do
                victim=${'$'}{entry%%:*}; identity=${'$'}{entry#*:}; tries=0
                [ "${'$'}victim" = "${'$'}protected" ] && continue
                while stat "${'$'}victim" 2>/dev/null && [ "${'$'}born" = "${'$'}identity" ] && [ "${'$'}state" != Z ] && [ "${'$'}state" != X ]; do
                    [ "${'$'}tries" -lt 100 ] || return 1
                    "${'$'}sleeper" 0.001 3<&- </dev/null
                    tries=${'$'}((tries+1))
                done
            done
        }
        token_paths() {
            "${'$'}grep" -l -z -F -x "FLOWGLASS_EXECUTION_TOKEN=${'$'}token" /proc/[0-9]*/environ 2>/dev/null
        }
        freeze_tokened() {
            token_victims=; token_seen=" "; rounds=0
            while [ "${'$'}rounds" -lt 100 ]; do
                added=0
                for path in ${'$'}(token_paths); do
                    pid=${'$'}{path#/proc/}; pid=${'$'}{pid%/environ}
                    case "${'$'}pid" in ''|*[!0-9]*) continue;; esac
                    case "${'$'}token_seen" in *" ${'$'}pid "*) continue;; esac
                    stat "${'$'}pid" 2>/dev/null || continue
                    identity=${'$'}born
                    if [ "${'$'}state" != Z ] && [ "${'$'}state" != X ]; then
                        kill -STOP "${'$'}pid" 2>/dev/null || return 1
                        tries=0
                        while [ "${'$'}state" != T ] && [ "${'$'}state" != Z ] && [ "${'$'}state" != X ]; do
                            [ "${'$'}tries" -lt 100 ] || return 1
                            "${'$'}sleeper" 0.001 3<&- </dev/null
                            stat "${'$'}pid" 2>/dev/null || return 1
                            [ "${'$'}born" = "${'$'}identity" ] || return 1
                            tries=${'$'}((tries+1))
                        done
                    fi
                    token_victims="${'$'}pid:${'$'}identity ${'$'}token_victims"
                    token_seen="${'$'}token_seen${'$'}pid "
                    added=1
                done
                [ "${'$'}added" = 0 ] && return 0
                rounds=${'$'}((rounds+1))
            done
            return 1
        }
        kill_tokened() {
            protected=${'$'}{1:-0}
            for entry in ${'$'}token_victims; do
                victim=${'$'}{entry%%:*}; identity=${'$'}{entry#*:}
                [ "${'$'}victim" = "${'$'}protected" ] && continue
                stat "${'$'}victim" 2>/dev/null || continue
                [ "${'$'}born" = "${'$'}identity" ] || return 1
                [ "${'$'}state" = Z ] || [ "${'$'}state" = X ] || kill -KILL "${'$'}victim" || return 1
            done
        }
        tokened_active() {
            for path in ${'$'}(token_paths); do
                pid=${'$'}{path#/proc/}; pid=${'$'}{pid%/environ}
                stat "${'$'}pid" 2>/dev/null || continue
                [ "${'$'}state" = Z ] || [ "${'$'}state" = X ] || return 0
            done
            return 1
        }
        finish() {
            trap '' USR1 HUP INT TERM
            # Freeze before discovering children: stopped parents cannot fork
            # or reap, so the recorded pid/starttime remains reserved. The
            # inherited token also reaches a reparented session outside the tree.
            clean=1
            if [ "${'$'}isolated" = 1 ]; then kill -STOP "-${'$'}group" || clean=0; fi
            freeze_tree "${'$'}group" || clean=0
            freeze_tokened || clean=0
            # Discovery failure is not success, but still kill every known ID.
            # Keep the group leader frozen/alive until the group signal so its
            # numeric PGID cannot be reused between leaf cleanup and that signal.
            if [ "${'$'}isolated" = 1 ]; then
                kill_tree "${'$'}group" || clean=0
                kill_tokened "${'$'}group" || clean=0
                kill -KILL "-${'$'}group" 2>/dev/null || clean=0
            else
                kill_tree || clean=0
                kill_tokened || clean=0
            fi
            n=0; active=${'$'}isolated
            while [ "${'$'}active" = 1 ] && [ "${'$'}n" -lt 100 ]; do
                active=0
                for path in /proc/[0-9]*/stat; do
                    pid=${'$'}{path#/proc/}; pid=${'$'}{pid%/stat}
                    stat "${'$'}pid" 2>/dev/null || continue
                    if [ "${'$'}pgid" = "${'$'}group" ] && [ "${'$'}state" != Z ] && [ "${'$'}state" != X ]; then active=1; fi
                done
                [ "${'$'}active" = 0 ] && break
                "${'$'}sleeper" 0.01 3<&- </dev/null || break
                n=${'$'}((n+1))
            done
            [ "${'$'}active" = 0 ] || clean=0
            n=0
            while tokened_active && [ "${'$'}n" -lt 100 ]; do
                "${'$'}sleeper" 0.01 3<&- </dev/null || break
                n=${'$'}((n+1))
            done
            if tokened_active; then clean=0; fi
            if [ "${'$'}clean" = 1 ]; then wait "${'$'}group" 2>/dev/null; fi
            # Also stop the EOF monitor, watchdog and control sleeps. A worker
            # stuck in kernel I/O produces no DONE, never a false cleanup pass.
            if [ "${'$'}own_group" != 1 ]; then
                # Never signal the su client's/shared app group in fallback mode.
                for helper in ${'$'}watchdog ${'$'}monitor; do
                    if stat "${'$'}helper" 2>/dev/null && [ "${'$'}ppid" = "${'$'}${'$'}" ]; then
                        freeze_tree "${'$'}helper" || clean=0
                        if kill_tree; then wait "${'$'}helper" 2>/dev/null; else clean=0; fi
                    fi
                done
            fi
            if [ "${'$'}clean" = 1 ]; then printf '\036%s:DONE\n' "${'$'}token"; fi
            if [ "${'$'}own_group" = 1 ]; then kill -KILL "-${'$'}${'$'}"; fi
            exit 0
        }
        trap 'finish' USR1 HUP INT TERM
        parent=${'$'}${'$'}
        duration=$(printf '%s.%03d' "${'$'}((ticks/100))" "${'$'}((ticks%100*10))")
        ( "${'$'}sleeper" "${'$'}duration" 3<&- </dev/null; kill -USR1 "${'$'}parent" ) &
        watchdog=${'$'}!; monitor=
        printf '\036%s:READY\n' "${'$'}token"
        if IFS= read -r go <&3 && [ "${'$'}go" = GO ] && [ "${'$'}stop" = 0 ]; then
            ( IFS= read -r ignored <&3; kill -USR1 "${'$'}parent" ) &
            monitor=${'$'}!
            kill -CONT "${'$'}group"
            n=0
            while [ "${'$'}n" -lt "${'$'}ticks" ]; do
                stat "${'$'}group" || break
                [ "${'$'}state" = T ] && break
                [ "${'$'}state" = Z ] && break
                "${'$'}sleeper" 0.01 3<&- </dev/null || break
                n=${'$'}((n+1))
            done
        fi
        finish
    """.trimIndent()
}
