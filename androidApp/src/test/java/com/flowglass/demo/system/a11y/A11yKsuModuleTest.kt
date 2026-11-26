package com.flowglass.demo.system.a11y

import com.flowglass.demo.system.exec.ProcessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yKsuModuleTest {

    private class FakeRunner(private val results: ArrayDeque<ProcessResult>) : A11yKsuModuleRunner {
        val scripts = mutableListOf<String>()
        override suspend fun run(script: String): ProcessResult {
            scripts += script
            return results.removeFirstOrNull() ?: ProcessResult(0, "", false)
        }
    }

    private fun ok() = ProcessResult(0, "", false)
    private fun fail() = ProcessResult(1, "", false)

    @Test
    fun `config is one component per line`() {
        assertEquals("a/a.A\nb/b.B\n", A11yKsuModule.configText(setOf("b/b.B", "a/a.A")))
        assertEquals("\n", A11yKsuModule.configText(emptySet()))
    }

    @Test
    fun `install writes module, script and config`() = runBlocking {
        val runner = FakeRunner(ArrayDeque(listOf(ok(), ok())))
        val result = A11yKsuModule.install(runner, "#!/system/bin/sh\necho hi\n", setOf("a/a.A"))
        assertEquals(0, result.exitCode)
        assertEquals(2, runner.scripts.size)
        assertTrue(runner.scripts[0].contains("id=flowglass_a11y_keepalive"))
        assertTrue(runner.scripts[0].contains("flowglass_a11y_keepalive/service.sh"))
        assertTrue(runner.scripts[1].contains("a/a.A"))
        assertTrue(runner.scripts[1].contains(A11yKsuModule.CONF))
    }

    @Test
    fun `install script keeps heredoc terminators at column zero`() {
        val script = A11yKsuModule.installScript("#!/system/bin/sh\necho hi\n")
        assertEquals("set -e", script.lines().first())
        assertTrue(script.lines().contains("FLOWGLASS_MODULE"))
        assertTrue(script.lines().contains("FLOWGLASS_SERVICE"))
    }

    @Test
    fun `install stops when the module write fails`() = runBlocking {
        val runner = FakeRunner(ArrayDeque(listOf(fail())))
        assertEquals(1, A11yKsuModule.install(runner, "x", setOf("a/a.A")).exitCode)
        assertEquals(1, runner.scripts.size)
    }

    @Test
    fun `uninstall removes module and state`() = runBlocking {
        val runner = FakeRunner(ArrayDeque(listOf(ok())))
        A11yKsuModule.uninstall(runner)
        val script = runner.scripts.single()
        assertTrue(script.contains("rm -rf"))
        assertTrue(script.contains(A11yKsuModule.DIR))
        assertTrue(script.contains(A11yKsuModule.STATE_DIR))
    }

    @Test
    fun `readLog keeps the tail`() = runBlocking {
        val runner = FakeRunner(ArrayDeque(listOf(ProcessResult(0, "0123456789", false))))
        assertEquals("456789", A11yKsuModule.readLog(runner, maxBytes = 6))
    }

    @Test
    fun `isInstalled uses the exit code`() = runBlocking {
        assertTrue(A11yKsuModule.isInstalled(FakeRunner(ArrayDeque(listOf(ok())))))
        assertFalse(A11yKsuModule.isInstalled(FakeRunner(ArrayDeque(listOf(fail())))))
    }
}
