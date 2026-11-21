package com.flowglass.demo.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreIdReservationTest {
    private fun command(id: Long, title: String, itemId: Long): Cmd = Cmd(
        id = id,
        auto = true,
        ic = Ic.Bolt,
        title = title,
        desc = "",
        triggers = listOf(CommandItem(itemId, "WiFi", "connected|", Ic.Wifi, "trigger.wifi")),
        actions = listOf(CommandItem(itemId + 1, "执行 Shell(Root)", "echo hi", Ic.Terminal, "action.root_shell")),
        enabled = false,
    )

    private fun backupWithHighIds(): PersistedAppState {
        val high = newId() + 5000
        return PersistedAppState(
            autoCmds = listOf(command(high, "恢复的指令", high + 10)),
            oneTaps = emptyList(),
            logs = listOf(RunLog(high + 20, "恢复的日志", "测试", "10:00", true, Ic.Bolt)),
            running = false,
            mode = 1,
        )
    }

    @Test
    fun restoreReservesEveryRestoredIdBeforeAllocatingNewObjects() {
        val state = AppState()
        val document = backupWithHighIds()
        val restoredMax = (document.autoCmds.map { it.id } +
            document.autoCmds.flatMap { it.triggers + it.conditions + it.actions }.map { it.id } +
            document.logs.map { it.id }).max()

        state.replaceWith(document)

        val allocated = newId()
        assertTrue(
            "恢复后分配的新 ID 不能与恢复的对象重合：新 ID=$allocated，恢复最大 ID=$restoredMax",
            allocated > restoredMax,
        )
        val liveIds = (state.autoCmds.flatMap { cmd ->
            listOf(cmd.id) + (cmd.triggers + cmd.conditions + cmd.actions).map { it.id }
        } + state.logs.map { it.id }).toSet()
        assertTrue("新 ID 必须未在恢复的文档中使用", allocated !in liveIds)
    }
}
