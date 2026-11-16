package com.flowglass.demo.system.exec

import com.flowglass.demo.system.RootInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSessionTest {
    private val root = RootInfo(
        suFound = true,
        granted = true,
        uidLine = "uid=0(root)",
        manager = "KernelSU",
        suPath = "/system/bin/su",
    )

    @Test fun aNormalCommandReusesTheSameShellAndParsesTheFrames() = runBlocking {
        val shell = FakeShell()
        val session = RootSession { shell }

        val result = session.execute(root, "echo hello", timeoutMs = 1_000)

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.output)
        assertEquals(1, shell.starts)
        assertEquals(listOf("echo hello"), shell.commands)
        assertNull(shell.killed)
    }

    @Test fun aTimeoutKillsTheReportedProcessGroupAndDestroysTheSession() = runBlocking {
        val shell = FakeShell().apply { emitExit = false }
        val session = RootSession { shell }

        val result = session.execute(root, "sleep 99", timeoutMs = 20)

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertEquals(42L to true, shell.killed)
        assertTrue(shell.destroyed)
        assertTrue(result.output.contains("命令超时"))
    }

    @Test fun closingAnIdleSessionDestroysTheShell() = runBlocking {
        val shell = FakeShell()
        val session = RootSession { shell }
        session.execute(root, "echo once", timeoutMs = 1_000)

        session.close()

        assertTrue(shell.destroyed)
    }

    @Test fun outputOverflowStopsTheCommandAndReportsATruncatedResult() = runBlocking {
        val shell = FakeShell().apply { output = "x".repeat(200) }
        val session = RootSession { shell }

        val result = session.execute(root, "cat big", timeoutMs = 1_000, outputLimit = 20)

        assertTrue(result.truncated)
        assertTrue(result.output.length <= 20)
        assertEquals(42L to true, shell.killed)
        assertTrue(shell.destroyed)
    }

    private class FakeShell : RootShell {
        var starts = 0
        var destroyed = false
        var killed: Pair<Long, Boolean>? = null
        var emitExit = true
        var output = "hello"
        val commands = mutableListOf<String>()
        private val lines = Channel<String>(Channel.UNLIMITED)

        override suspend fun start(): Boolean {
            starts += 1
            return true
        }

        override fun isAlive(): Boolean = !destroyed

        override suspend fun request(id: String, command: String) {
            commands += command
            lines.send("__FLOWGLASS_BEGIN__:$id")
            lines.send("__FLOWGLASS_PID__:$id:42:1")
            output.lineSequence().forEach { lines.send(it) }
            if (emitExit) lines.send("__FLOWGLASS_EXIT__:$id:0")
        }

        override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()

        override fun destroy() {
            destroyed = true
            lines.close()
        }

        override suspend fun killGroup(pid: Long, grouped: Boolean) {
            killed = pid to grouped
        }
    }
}
