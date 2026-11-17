package com.flowglass.demo.system.exec

import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import com.flowglass.demo.xposed.ModuleStatus
import com.flowglass.engine.ExecutionLayer
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
        actions = ids.map { CommandItem(it, "任务$it", "", Ic.Bolt, "action.copy_clipboard") },
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

    @Test fun injectedExecutorSkipsUnknownTypesJustLikeNativeExecution() = runTest {
        val invoked = mutableListOf<Long>()
        val command = command(1).copy(actions = listOf(
            CommandItem(1, "复制到剪贴板", "must not execute", Ic.Bolt, "future.action"),
        ))
        val injected = ExecutionEngine(RuntimeEnvironment.getApplication()) { _, task ->
            invoked += task.id
            TaskOutcome(StepStatus.SUCCESS, "should not run")
        }.run(command, "测试")
        val native = ExecutionEngine(RuntimeEnvironment.getApplication()).run(command, "测试")

        assertTrue("unknown types must not reach the injected executor", invoked.isEmpty())
        assertEquals(native.steps, injected.steps)
        assertEquals(StepStatus.SKIPPED, injected.steps.single().status)
    }

    @Test fun manualRunSkipsInvalidTriggersAndConditions() = runTest {
        val invoked = mutableListOf<Long>()
        val command = Cmd(
            id = 703,
            auto = true,
            ic = Ic.Bolt,
            title = "手动测试自动指令",
            desc = "",
            triggers = listOf(CommandItem(10, "时间", "不是合法时刻", Ic.Clock, "trigger.time")),
            conditions = listOf(CommandItem(11, "时间段", "不是合法时间段", Ic.Clock, "condition.time_range")),
            actions = listOf(CommandItem(12, "复制到剪贴板", "手动测试内容", Ic.Copy, "action.copy_clipboard")),
        )
        val result = ExecutionEngine(RuntimeEnvironment.getApplication()) { _, task ->
            invoked += task.id
            TaskOutcome(StepStatus.SUCCESS, "完成")
        }.run(command, "手动测试")

        assertEquals(listOf(12L), invoked)
        assertTrue(result.ok)
        assertEquals(listOf(StepStatus.SUCCESS), result.steps.map { it.status })
    }

    @Test fun injectedActionRecordsTheNormalLayerInTheStepLog() = runTest {
        val result = ExecutionEngine(RuntimeEnvironment.getApplication()) { _, _ ->
            TaskOutcome(StepStatus.SUCCESS, "完成")
        }.run(command(1), "测试")

        assertEquals(ExecutionLayer.NORMAL, result.steps.single().layer)
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

    @Test fun rootActionRefreshKeepsReadyLsposed() = runTest {
        val app = RuntimeEnvironment.getApplication()
        try {
            SystemHookSettings.setEnabled(app, true)
            SystemHookSettings.markLoaded(app, hookCount = 3)
            SystemHookSettings.markHeartbeat(app, hookCount = 3)
            ModuleStatus.markModern(
                apiVersion = 101,
                frameworkName = "LSPosed",
                frameworkVersion = "2.2",
                frameworkVersionCode = 3080L,
                scope = listOf("system", "com.flowglass.demo"),
            )
            assertTrue(SystemHookSettings.isAlive(app))
            LayerStatus.updateLsposed(active = true, systemHookReady = true)

            val rootAction = command(1).copy(
                actions = listOf(
                    CommandItem(
                        id = 1,
                        name = "强制停止",
                        parameter = "com.example.app",
                        ic = Ic.Bolt,
                        typeId = "action.force_stop",
                    ),
                ),
            )
            ExecutionEngine(app).run(rootAction, "测试")

            assertEquals(LayerState.READY, LayerStatus.current().lsposed)
        } finally {
            ModuleStatus.clearModern()
            SystemHookSettings.setEnabled(app, false)
            LayerStatus.updateLsposed(active = false, systemHookReady = false)
        }
    }
}