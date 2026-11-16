package com.flowglass.demo.ui

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommandTransferTest {
    private fun command(title: String = "测试指令", auto: Boolean = true): Cmd = Cmd(
        id = 100L,
        auto = auto,
        ic = Ic.Bolt,
        title = title,
        desc = "描述",
        triggers = listOf(CommandItem(101L, "WiFi", "connected|", Ic.Wifi, "trigger.wifi")),
        conditions = listOf(CommandItem(102L, "电量", "50–100", Ic.Battery, "condition.battery_range")),
        actions = listOf(
            CommandItem(103L, "执行 Shell(Root)", "echo hi", Ic.Terminal, "action.root_shell"),
        ),
        enabled = true,
    )

    @Test
    fun singleCommandExportHasVersionAndImportPreviewListsContents() {
        val state = AppState()
        val source = command()
        state.autoCmds.add(source)

        val json = exportCommandJson(state, source)
        assertTrue(json.contains("\"flowglass.command\""))
        assertTrue(json.contains("\"formatVersion\": 1"))

        val parsed = parseImport(json, state)
        assertEquals(ImportKind.COMMAND, parsed.kind)
        assertEquals(1, parsed.preview.commands.size)
        val preview = parsed.preview.commands.single()
        assertEquals(1, preview.triggerCount)
        assertEquals(1, preview.conditionCount)
        assertEquals(1, preview.actionCount)
        assertTrue(preview.sensitive.contains("执行 Shell(Root)"))
        assertTrue(preview.rootRequired.contains("执行 Shell(Root)"))
    }

    @Test
    fun commandImportReallocatesIdsSuffixesNameAndDisablesAuto() {
        val state = AppState()
        val source = command()
        state.autoCmds.add(source)
        val parsed = parseImport(exportCommandJson(state, source), state)

        state.applyCommandImport(parsed)

        assertEquals(2, state.autoCmds.size)
        val imported = state.autoCmds.last()
        assertNotEquals(source.id, imported.id)
        assertFalse(imported.enabled)
        assertEquals("测试指令（导入）", imported.title)
        assertNotEquals(source.actions.single().id, imported.actions.single().id)
        assertTrue(state.engineRules.any { it.name == imported.title && !it.enabled })
    }

    @Test
    fun backupRoundTripKeepsGlobalsAndThemeSetting() {
        val state = AppState()
        state.autoCmds.add(command())
        state.engineGlobals = mapOf("greeting" to JsonPrimitive("hello"))
        state.mode = 2
        state.setEngineRunning(false)
        val json = exportBackupJson(
            state, ThemeMode.DARK,
            mapOf("keepalive.fgs" to "true", "system_hook.enabled" to "false"),
        )

        val parsed = parseImport(json, state)
        assertEquals(ImportKind.BACKUP, parsed.kind)
        assertEquals(ThemeMode.DARK, parsed.preview.themeMode)
        assertEquals(JsonPrimitive("hello"), parsed.converted.engineGlobals["greeting"])
        assertEquals(2, parsed.converted.mode)
        assertFalse(parsed.converted.running)
        assertEquals(
            mapOf("keepalive.fgs" to "true", "system_hook.enabled" to "false"),
            parsed.settings?.appSettings,
        )
    }

    @Test
    fun backupRestoreWritesSafetyFileBeforeReplacingState() {
        RuntimeEnvironment.getApplication()
        val state = AppState()
        state.autoCmds.add(command("旧数据"))
        state.engineGlobals = mapOf("old" to JsonPrimitive("1"))
        val target = AppState()
        target.oneTaps.add(command("新数据", auto = false).copy(triggers = emptyList(), conditions = emptyList()))
        val parsed = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)

        val safety = restoreBackup(state, parsed, Files.createTempDirectory("transfer-test").toFile())

        assertTrue(safety.exists())
        assertTrue(safety.readText().contains("旧数据"))
        assertEquals("新数据", state.oneTaps.single().title)
        assertTrue(state.engineGlobals.isEmpty())
    }

    @Test
    fun malformedOrUnsupportedFilesAreRejectedWithoutChangingState() {
        val state = AppState()
        state.autoCmds.add(command("保持不变"))
        val before = state.autoCmds.single()

        for (bad in listOf(
            """{"format":"flowglass.command","formatVersion":99,"document":{}}""",
            """{"format":"unknown","formatVersion":1}""",
            """{"format":"flowglass.command","formatVersion":1,"document":{}}""",
            "not json",
        )) {
            runCatching { parseImport(bad, state) }
            assertEquals(before, state.autoCmds.single())
        }
    }

    @Test
    fun uniqueTitleAddsStableSuffixes() {
        val used = mutableSetOf("名称")
        assertEquals("名称（导入）", uniqueCommandTitle("名称", true, used))
        used += "名称（导入）"
        assertEquals("名称（导入 2）", uniqueCommandTitle("名称", true, used))
    }

    @Test
    fun sensitiveActionDetectionIncludesNestedRootAndHttp() {
        val cmd = command().copy(
            actions = listOf(
                CommandItem(
                    1L, "如果", "", Ic.Bolt, "block.if",
                    children = listOf(
                        CommandItem(2L, "HTTP 请求", "GET https://example.com", Ic.Wifi, "action.http_request"),
                    ),
                ),
            ),
        )
        val preview = parseImport(exportCommandJson(AppState().also { it.autoCmds += cmd }, cmd), AppState())
            .preview.commands.single()
        assertTrue(preview.sensitive.contains("HTTP 请求"))
    }

}
