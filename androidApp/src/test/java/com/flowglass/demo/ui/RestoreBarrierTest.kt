package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.os.Looper
import com.flowglass.demo.system.exec.ExecutionEngine
import com.flowglass.demo.system.exec.StepStatus
import com.flowglass.demo.system.exec.TaskOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * STO-12：恢复会整份替换规则与变量。旧世代在途任务不能把结果写进新文档，恢复也必须
 * 丢弃旧的撤销、历史面板和菜单状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreBarrierTest {
    private fun replacement(): PersistedAppState {
        val cmd = Cmd(
            id = 7, auto = false, ic = Ic.Bolt, title = "恢复后", desc = "",
            actions = listOf(CommandItem(8, "延迟", "1", Ic.Timer, "action.delay")),
        )
        return PersistedAppState(emptyList(), listOf(cmd), emptyList(), true, 1)
    }

    @Test
    fun restoreAdvancesTheDocumentEpoch() {
        val state = AppState()
        val before = state.documentEpoch
        state.replaceWith(replacement())
        assertTrue("恢复必须开启新世代", state.documentEpoch > before)
    }

    @Test
    fun restoreDropsUndoAndTransientUiState() {
        val state = AppState()
        state.undo = UndoInfo(state.create(auto = false), 0, label = "删除")
        state.historyOpen = true

        state.replaceWith(replacement())

        assertNull("旧撤销会指回被替换的文档", state.undo)
        assertFalse("恢复后不应保留历史面板", state.historyOpen)
        assertEquals("恢复后", state.oneTaps.single().title)
    }

    @Test
    fun restoreClearsExecutionBadges() {
        val state = AppState()
        state.executing[7] = 1
        state.executionMessages[7] = "运行中"

        state.replaceWith(replacement())

        assertTrue(state.executing.isEmpty())
        assertTrue(state.executionMessages.isEmpty())
    }

    @Test
    fun aStaleRunCannotWriteLogsOrRefreshLastRun() {
        val context = RuntimeEnvironment.getApplication()
        val gate = CompletableDeferred<Unit>()
        val engine = ExecutionEngine(context) { _, _ ->
            withContext(NonCancellable) { gate.await() }
            TaskOutcome(StepStatus.SUCCESS, "完成")
        }
        val state = AppState()
        state.bindExecutionEngine(engine)
        val cmd = state.create(auto = false)
        state.addItem(
            cmd.id,
            CommandSection.ACTIONS,
            CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.delay" },
        )
        val running = state.find(cmd.id)!!
        val job = state.execute(running, CoroutineScope(Dispatchers.Main + SupervisorJob()))!!
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        state.replaceWith(replacement())
        gate.complete(Unit)
        val deadline = System.currentTimeMillis() + 5_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue("旧执行的日志不得进入恢复后的文档", state.logs.isEmpty())
        assertEquals("从未", state.oneTaps.single().lastRun)
    }

    @Test
    fun aStaleVariableWriterIsDropped() {
        val context = RuntimeEnvironment.getApplication()
        val engine = ExecutionEngine(context)
        val state = AppState()
        state.bindExecutionEngine(engine)
        val writer = requireNotNull(engine.variableWriter)
        val stale = state.documentEpoch
        state.replaceWith(replacement())

        writer("rule-x", emptyMap(), mapOf("late" to 1), stale)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("旧世代的变量写入必须丢弃", state.engineGlobals["late"] == null)

        writer("rule-x", emptyMap(), mapOf("fresh" to 2), state.documentEpoch)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, (state.engineGlobals["fresh"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test
    fun restoringADocumentDropsPendingFilePayloads() {
        val state = AppState()
        state.create(auto = true)
        state.pendingExportText = "old export"
        state.pendingShareJson = "old share"
        state.sharingCmdId = 7L

        state.replaceWith(state.snapshot())

        assertNull(state.pendingExportText)
        assertNull(state.pendingShareJson)
        assertNull(state.sharingCmdId)
    }
}
