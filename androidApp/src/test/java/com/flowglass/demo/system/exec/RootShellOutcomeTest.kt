package com.flowglass.demo.system.exec

import com.flowglass.demo.system.RootInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellOutcomeTest {
    private val root = RootInfo(
        suFound = true,
        granted = true,
        uidLine = "uid=0(root)",
        manager = "KernelSU",
        suPath = "/system/bin/su",
    )

    @Test
    fun aFailedCommandStillPublishesBothStreamsAndTheExitCode() = runBlocking {
        val outcome = RootTasks.rootShell(root, "mixed") {
            ProcessResult(7, "out", timedOut = false, stderr = "err")
        }

        assertEquals(StepStatus.FAILURE, outcome.status)
        assertEquals("out", outcome.outputs["shell.stdout"])
        assertEquals("err", outcome.outputs["shell.stderr"])
        assertEquals(7, outcome.outputs["shell.code"])
        assertTrue(outcome.detail, outcome.detail.contains("退出码 7"))
        assertTrue(outcome.detail, outcome.detail.contains("err"))
    }

    @Test
    fun aTimedOutCommandPublishesThePartialStreamsAndMinusOne() = runBlocking {
        val outcome = RootTasks.rootShell(root, "slow") {
            ProcessResult(-1, "partial", timedOut = true, stderr = "warning")
        }

        assertEquals(StepStatus.FAILURE, outcome.status)
        assertEquals("partial", outcome.outputs["shell.stdout"])
        assertEquals("warning", outcome.outputs["shell.stderr"])
        assertEquals(-1, outcome.outputs["shell.code"])
        assertTrue(outcome.detail, outcome.detail.contains("超时"))
    }

    @Test
    fun aSuccessfulCommandCanStillCarryStderr() = runBlocking {
        val outcome = RootTasks.rootShell(root, "ok") {
            ProcessResult(0, "out", timedOut = false, stderr = "warn")
        }

        assertEquals(StepStatus.SUCCESS, outcome.status)
        assertEquals("out", outcome.outputs["shell.stdout"])
        assertEquals("warn", outcome.outputs["shell.stderr"])
        assertEquals(0, outcome.outputs["shell.code"])
        assertTrue(outcome.detail, outcome.detail.contains("warn"))
    }

    @Test
    fun aSessionFailureStillPublishesTheThreeOutputs() = runBlocking {
        val outcome = RootTasks.rootShell(root, "x") {
            error("Root 会话启动失败")
        }

        assertEquals(StepStatus.FAILURE, outcome.status)
        assertEquals("", outcome.outputs["shell.stdout"])
        assertEquals("", outcome.outputs["shell.stderr"])
        assertEquals(-1, outcome.outputs["shell.code"])
        assertTrue(outcome.detail, outcome.detail.contains("Root 会话启动失败"))
    }

    @Test
    fun aSuccessfulCommandUsesTheSameOutputKeys() = runBlocking {
        val outcome = RootTasks.rootShell(root, "ok") {
            ProcessResult(0, "out", timedOut = false, stderr = "")
        }

        assertEquals(StepStatus.SUCCESS, outcome.status)
        assertEquals("out", outcome.outputs["shell.stdout"])
        assertEquals("", outcome.outputs["shell.stderr"])
        assertEquals(0, outcome.outputs["shell.code"])
    }
}
