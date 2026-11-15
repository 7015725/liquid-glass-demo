package com.flowglass.demo.ui

import com.flowglass.demo.system.exec.ExecutionEngine
import com.flowglass.demo.system.exec.StepResult
import com.flowglass.demo.system.exec.StepStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** C1 review: a 10k-step run must not store 10k steps or write a huge commands.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunLogCapTest {
    @get:Rule val folder = TemporaryFolder()

    private fun engine() = ExecutionEngine(RuntimeEnvironment.getApplication())

    private fun cmd() = Cmd(id = 1, auto = false, ic = Ic.Bolt, title = "长任务", desc = "", actions = emptyList())

    @Test
    fun capKeepsHeadTailMarkerAndTheFailure() {
        val steps = (0 until 9_999).map { index ->
            StepResult(
                itemId = 10,
                name = "步骤 $index",
                status = if (index == 5_000) StepStatus.FAILURE else StepStatus.SUCCESS,
                detail = "x",
                durationMs = 0,
            )
        }
        val capped = engine().capSteps(cmd(), steps)
        assertTrue("capped=${capped.size}", capped.size <= 301)
        assertTrue(capped.any { it.status == StepStatus.FAILURE })
        val marker = capped.single { it.name.startsWith("已省略") }
        assertTrue(marker.name.contains("个步骤"))
        assertTrue(marker.name.removePrefix("已省略 ").removeSuffix(" 个步骤").toInt() > 0)
    }

    @Test
    fun savedFileStaysSmallAndTheFailureSurvivesAReload() {
        val steps = (0 until 9_999).map { index ->
            StepResult(
                itemId = 10,
                name = "步骤 $index",
                status = if (index == 5_000) StepStatus.FAILURE else StepStatus.SUCCESS,
                detail = "步骤详情",
                durationMs = 0,
            )
        }
        val capped = engine().capSteps(cmd(), steps)
        val log = RunLog(
            id = 99, title = "长任务", reason = "手动执行", time = "10:00", ok = false,
            ic = Ic.Bolt, detail = "失败", steps = capped, skipped = false,
        )
        val state = PersistedAppState(
            autoCmds = emptyList(), oneTaps = listOf(cmd()), logs = listOf(log),
            running = true, mode = 1,
        )
        val file = File(folder.root, "commands.json")
        val text = AppStateJson.encode(state)
        val bytes = text.toByteArray(Charsets.UTF_8)
        println("CAPPED_BYTES=${bytes.size} STEPS=${capped.size}")
        assertTrue("bytes=${bytes.size}", bytes.size < 300_000)

        file.writeText(text)
        val loaded = CommandStore(file).load().saved
        val loadedLog = loaded?.logs?.single()
        assertTrue(loadedLog != null)
        assertTrue(loadedLog!!.steps.any { it.status == StepStatus.FAILURE })
        assertFalse(loadedLog.steps.any { it.name == "步骤 6000" })
    }
}
