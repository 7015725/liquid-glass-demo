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
    fun `script uses the absolute settings path and verifies the write`() {
        val script = A11ySelfService.enableScript()
        assertTrue(script.contains("S=/system/bin/settings"))
        assertTrue(script.contains(A11ySelfService.COMPONENT))
        // Appends instead of overwriting, keeps existing entries.
        assertTrue(script.contains("\${cur:+\$cur:}\$C"))
        // Idempotent and never reports success without re-reading.
        assertTrue(script.contains("echo 'already'"))
        assertTrue(script.contains("after=\$(\$S get secure enabled_accessibility_services"))
        assertTrue(script.contains("verify_failed"))
        // The write error must be carried out, otherwise the UI can only say "put_failed".
        assertTrue(script.contains("put_failed:\${out:-no_output}"))
    }

    @Test
    fun `enable maps the script verdict`() = runBlocking {
        assertEquals(
            A11ySelfService.Result.Enabled,
            A11ySelfService.enable(FakeRunner(ProcessResult(0, "enabled\n", false))),
        )
        assertEquals(
            A11ySelfService.Result.Already,
            A11ySelfService.enable(FakeRunner(ProcessResult(0, "already\n", false))),
        )
    }

    @Test
    fun `enable surfaces the failure detail`() = runBlocking {
        val verify = A11ySelfService.enable(FakeRunner(ProcessResult(0, "verify_failed:a:a.A\n", false)))
        assertTrue(verify is A11ySelfService.Result.Failed)
        assertEquals("verify_failed:a:a.A", (verify as A11ySelfService.Result.Failed).detail)

        val put = A11ySelfService.enable(FakeRunner(ProcessResult(4, "put_failed\n", false)))
        assertTrue(put is A11ySelfService.Result.Failed)
        assertTrue((put as A11ySelfService.Result.Failed).detail.contains("put_failed"))
    }
}
