package com.flowglass.demo.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The store refuses to load a document that still carries the retired method-hook trigger. Import
 * must apply the same policy: accepting it would write a file that bricks the next launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportCompatibilityTest {
    private fun retiredDocument(): PersistedAppState = PersistedAppState(
        autoCmds = listOf(
            Cmd(
                id = 501L,
                auto = true,
                ic = Ic.Bolt,
                title = "旧方法钩子规则",
                desc = "",
                triggers = listOf(CommandItem(502L, "方法钩子", "com.example|onCreate", Ic.Bolt, "trigger.method_hook")),
                actions = listOf(CommandItem(503L, "屏幕提示", "ok", Ic.Bolt, "action.toast")),
                enabled = false,
            ),
        ),
        oneTaps = emptyList(),
        logs = emptyList(),
        running = false,
        mode = 1,
    )

    @Test
    fun retiredMethodHookTriggerIsRejectedBeforeItCanBeWritten() {
        // A real export never carries the retired type (conversion rewrites it), so the fixture is
        // a valid backup whose trigger type is swapped for the retired id — what an old file holds.
        val healthy = PersistedAppState(
            autoCmds = listOf(
                Cmd(
                    id = 501L, auto = true, ic = Ic.Bolt, title = "旧方法钩子规则", desc = "",
                    triggers = listOf(CommandItem(502L, "WiFi", "connected|", Ic.Wifi, "trigger.wifi")),
                    actions = listOf(CommandItem(503L, "屏幕提示", "ok", Ic.Bolt, "action.toast")),
                    enabled = false,
                ),
            ),
            oneTaps = emptyList(), logs = emptyList(), running = false, mode = 1,
        )
        val exported = exportBackupJson(healthy, ThemeMode.SYSTEM)
        assertTrue("fixture must contain the current trigger type", exported.contains("\"trigger.wifi\""))
        val json = exported.replace("\"trigger.wifi\"", "\"trigger.method_hook\"")
        val state = AppState()

        try {
            parseImport(json, state)
            fail("含退役方法钩子触发器的备份必须在导入阶段被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "拒绝理由应说明触发器已退役，实际=${expected.message}",
                expected.message.orEmpty().contains("已退役"),
            )
        }
        assertTrue("拒绝后不得修改现有状态", state.autoCmds.isEmpty() && state.oneTaps.isEmpty())
    }
}
