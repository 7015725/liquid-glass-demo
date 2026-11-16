package com.flowglass.demo.system.exec

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The root scan has to see a full package list, not the 500-character shell-action budget. */
class BoundedProcessOutputTest {
    @Test fun aReadOnlyScanCanExceedTheShellActionBudget() = runBlocking {
        val result = BoundedProcess.run(
            listOf("/bin/sh", "-c", "i=0; while [ \$i -lt 300 ]; do echo 0123456789; i=\$((i+1)); done"),
            timeoutMs = 10_000,
            outputLimit = 4096,
        )
        assertEquals(0, result.exitCode)
        assertFalse(result.truncated)
        assertTrue(result.output.length > 500)
        assertTrue(result.output.contains("0123456789"))
    }

    @Test fun truncatedOutputIsReportedInsteadOfSilentlyShortened() = runBlocking {
        val result = BoundedProcess.run(
            listOf("/bin/sh", "-c", "i=0; while [ \$i -lt 200 ]; do echo 0123456789; i=\$((i+1)); done"),
            timeoutMs = 10_000,
            outputLimit = 512,
        )
        assertEquals(512, result.output.length)
        assertTrue(result.truncated)
    }
}
