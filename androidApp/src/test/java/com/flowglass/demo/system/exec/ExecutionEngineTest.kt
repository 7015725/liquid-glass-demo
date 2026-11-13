package com.flowglass.demo.system.exec

import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExecutionEngineTest {
    private fun command(vararg ids: Long) = Cmd(
        id = 701,
        auto = false,
        ic = Ic.Bolt,
        title = "引擎测试",
        desc = "",
        actions = ids.map { CommandItem(it, "任务$it", "", Ic.Bolt) },
    )

    @Test fun failureStopsExecutorAndMarksLaterTasksSkipped() = runTest {
        val invoked = mutableListOf<Long>()
        val engine = ExecutionEngine(RuntimeEnvironment.getApplication()) { _, task ->
            invoked += task.id
            if (task.id == 2L) TaskOutcome(StepStatus.FAILURE, "执行失败")
            else TaskOutcome(StepStatus.SUCCESS, "完成")
        }

        val result = engine.run(command(1, 2, 3), "测试")

        assertEquals(listOf(1L, 2L), invoked)
        assertEquals(listOf(StepStatus.SUCCESS, StepStatus.FAILURE, StepStatus.SKIPPED), result.steps.map { it.status })
        assertEquals("执行失败", result.detail)
        assertFalse(result.ok)
    }

    @Test fun unknownTaskIsSkippedWithoutCallingTheExecutor() = runTest {
        val result = ExecutionEngine(RuntimeEnvironment.getApplication())
            .run(command(1).copy(actions = listOf(CommandItem(1, "未来任务", "", Ic.Bolt, "future.action"))), "测试")

        assertEquals(StepStatus.SKIPPED, result.steps.single().status)
        assertTrue(result.steps.single().detail.contains("暂不支持"))
    }

    @Test fun aRepeatedInstructionIsIgnoredUntilTheFirstRunFinishes() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val engine = ExecutionEngine(RuntimeEnvironment.getApplication()) { _, _ ->
            calls++
            entered.complete(Unit)
            release.await()
            TaskOutcome(StepStatus.SUCCESS, "完成")
        }
        val command = command(1)
        val first = async { engine.run(command, "首次") }
        entered.await()
        try {
            val duplicate = engine.run(command, "重复")
            assertTrue(duplicate.ignored)
            assertTrue(duplicate.steps.isEmpty())
            assertEquals(1, calls)
        } finally {
            release.complete(Unit)
        }
        assertTrue(first.await().ok)
        assertFalse(engine.run(command, "完成后").ignored)
        assertEquals(2, calls)
    }
}