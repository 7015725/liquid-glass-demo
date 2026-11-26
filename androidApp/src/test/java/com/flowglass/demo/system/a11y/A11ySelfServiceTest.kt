package com.flowglass.demo.system.a11y

import com.flowglass.demo.system.exec.ProcessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A11ySelfServiceTest {

    private class FakeRunner(private val result: ProcessResult) : A11yKsuModuleRunner {
        val scripts = mutableListOf<String>()
        override suspend fun run(script: String): ProcessResult {
            scripts += script
            return result
        }
    }

    @Test
    fun `script appends our component and keeps the existing list`() {
        val script = A11ySelfService.enableScript()
        assertTrue(script.contains(A11ySelfService.COMPONENT))
        // Appends instead of overwriting: the existing value is reused.
        assertTrue(script.contains("\${cur:+\$cur:}"))
        assertTrue(script.contains("accessibility_enabled 1"))
        // Idempotent: running twice must not duplicate the entry.
        assertTrue(script.contains("*\":\$C:\"*"))
    }

    @Test
    fun `enable reports the script verdict`() = runBlocking {
        assertEquals("enabled", A11ySelfService.enable(FakeRunner(ProcessResult(0, "enabled\n", false))))
        assertEquals("already", A11ySelfService.enable(FakeRunner(ProcessResult(0, "already\n", false))))
        assertEquals("error", A11ySelfService.enable(FakeRunner(ProcessResult(0, "", false))))
        assertEquals("error", A11ySelfService.enable(FakeRunner(ProcessResult(1, "enabled\n", false))))
    }
}
