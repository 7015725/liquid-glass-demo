package com.flowglass.demo.ui

import android.os.Looper
import com.flowglass.demo.system.exec.ExecutionEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExecuteErrorTest {
    @Test
    fun anErrorFromAnActionIsRecordedAsAnInternalFailureInsteadOfCrashing() = runBlocking {
        val engine = ExecutionEngine(RuntimeEnvironment.getApplication()) { _, _ ->
            throw AssertionError("boom")
        }
        val state = AppState().apply { bindExecutionEngine(engine) }
        val command = Cmd(
            id = 90_001, auto = false, ic = Ic.Bell, title = "错误兜底", desc = "",
            actions = listOf(CommandItem(90_002, "发送通知", "|%{last}", Ic.Bell, "action.post_notification")),
        )
        state.oneTaps.add(command)

        val job = requireNotNull(state.execute(command, this, "错误测试"))
        try {
            withTimeout(5_000) {
                while (state.logs.isEmpty()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val log = state.logs.single()
            assertFalse(log.ok)
            assertEquals("内部错误：AssertionError: boom", log.detail)
            assertEquals("内部错误：AssertionError: boom", log.steps.single().detail)
            assertEquals(3, state.executing[command.id])
            assertEquals("内部错误：AssertionError: boom", state.executionMessages[command.id])
        } finally {
            job.cancel()
        }
    }
}
