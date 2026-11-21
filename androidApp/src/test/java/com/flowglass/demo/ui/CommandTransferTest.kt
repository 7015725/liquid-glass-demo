package com.flowglass.demo.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.flowglass.engine.GlobalVariableMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommandTransferTest {
    // CI has no TMPDIR: fall back to the JVM temp dir so the suite is environment-independent.
    private fun taskTempFolder(prefix: String): File = Files.createTempDirectory(
        (System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(System.getProperty("java.io.tmpdir"))).toPath(),
        prefix,
    ).toFile()

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
    fun commandExportKeepsGlobalDeclarationsAndNotesButNotValues() {
        val state = AppState()
        val source = command().copy(
            actions = listOf(
                CommandItem(103L, "执行 Shell(Root)", "echo {token}", Ic.Terminal, "action.root_shell"),
            ),
        )
        state.autoCmds.add(source)
        state.engineGlobals = mapOf("token" to JsonPrimitive("secret"))
        state.engineGlobalMeta = mapOf("token" to GlobalVariableMeta("text", "接口令牌"))

        val parsed = parseImport(exportCommandJson(state, source), state)

        assertTrue(parsed.document.globals.isEmpty())
        assertEquals(GlobalVariableMeta("text", "接口令牌"), parsed.document.globalMeta["token"])
        assertFalse(exportCommandJson(state, source).contains("secret"))
    }

    @Test
    fun commandExportOmitsGlobalsTheCommandDoesNotUse() {
        val state = AppState()
        val source = command().copy(
            actions = listOf(
                CommandItem(103L, "执行 Shell(Root)", "echo {used}", Ic.Terminal, "action.root_shell"),
            ),
        )
        state.autoCmds.add(source)
        state.engineGlobals = mapOf(
            "used" to JsonPrimitive("1"),
            "unused" to JsonPrimitive("2"),
        )
        state.engineGlobalMeta = mapOf(
            "used" to GlobalVariableMeta("text", "使用"),
            "unused" to GlobalVariableMeta("text", "未使用"),
        )

        val parsed = parseImport(exportCommandJson(state, source), state)

        assertEquals(setOf("used"), parsed.document.globalMeta.keys)
        assertFalse(exportCommandJson(state, source).contains("未使用"))
    }

    @Test
    fun commandImportMergesMissingGlobalMetadataWithoutValues() {
        val source = AppState()
        val sourceCommand = command("带全局变量").copy(
            actions = listOf(
                CommandItem(103L, "执行 Shell(Root)", "echo {token}", Ic.Terminal, "action.root_shell"),
            ),
        )
        source.autoCmds.add(sourceCommand)
        source.engineGlobals = mapOf("token" to JsonPrimitive("secret"))
        source.engineGlobalMeta = mapOf("token" to GlobalVariableMeta("text", "接口令牌"))
        val parsed = parseImport(exportCommandJson(source, sourceCommand), AppState())

        val target = AppState()
        target.applyCommandImport(parsed)

        assertEquals(GlobalVariableMeta("text", "接口令牌"), target.engineGlobalMeta["token"])
        assertTrue(target.engineGlobals.isEmpty())
    }

    @Test
    fun backupRoundTripKeepsGlobalsAndThemeSetting() {
        val state = AppState()
        state.autoCmds.add(command())
        state.engineGlobals = mapOf("greeting" to JsonPrimitive("hello"))
        state.engineGlobalMeta = mapOf("greeting" to GlobalVariableMeta("text", "问候语"))
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
        assertEquals(GlobalVariableMeta("text", "问候语"), parsed.converted.engineGlobalMeta["greeting"])
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

    @Test
    fun emptyFullBackupIsRestorable() {
        val state = AppState()

        val parsed = parseImport(exportBackupJson(state, ThemeMode.SYSTEM), state)

        assertEquals(ImportKind.BACKUP, parsed.kind)
        assertTrue(parsed.converted.autoCmds.isEmpty())
        assertTrue(parsed.converted.oneTaps.isEmpty())
    }

    @Test
    fun fullBackupExportRejectsPayloadBeyondImportBudget() {
        val state = AppState().also {
            it.engineGlobals = mapOf("payload" to JsonPrimitive("x".repeat(2_000_001)))
        }

        val error = runCatching { exportBackupJson(state, ThemeMode.SYSTEM) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("文件过大", error?.message)
    }

    @Test
    fun fullBackupExportRejectsExcessiveJsonDepth() {
        var nested: JsonElement = JsonPrimitive("leaf")
        repeat(140) { nested = JsonArray(listOf(nested)) }
        val state = AppState().also { it.engineGlobals = mapOf("nested" to nested) }

        val error = runCatching { exportBackupJson(state, ThemeMode.SYSTEM) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("文件嵌套过深", error?.message)
    }

    @Test
    fun singleCommandExportRejectsPayloadBeyondImportBudget() {
        val source = command().copy(
            actions = listOf(
                CommandItem(103L, "执行 Shell(Root)", "x".repeat(2_000_001), Ic.Terminal, "action.root_shell"),
            ),
        )
        val state = AppState().also { it.autoCmds.add(source) }

        val error = runCatching { exportCommandJson(state, source) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("文件过大", error?.message)
    }

    @Test
    fun safetyBackupUsesTheBackupRestoreEntry() {
        RuntimeEnvironment.getApplication()
        val state = AppState().also { it.autoCmds.add(command("恢复前")) }
        val target = AppState().also {
            it.oneTaps.add(command("恢复目标", auto = false).copy(triggers = emptyList(), conditions = emptyList()))
        }
        val parsed = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)
        val folder = taskTempFolder("transfer-safety")

        val safety = restoreBackup(state, parsed, folder)
        val restored = parseImport(safety.readText(), state)

        assertEquals(ImportKind.BACKUP, restored.kind)
        assertEquals("恢复前", restored.preview.commands.single().title)
    }

    @Test
    fun contextSafetyBackupContainsThemeVariablesLogsAndSafeAppSettings() {
        val context = RuntimeEnvironment.getApplication()
        AppearanceSettings.write(context, ThemeMode.DARK)
        context.getSharedPreferences("keepalive", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("fgs", true).apply()
        context.getSharedPreferences("keepalive", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("root_granted", true).apply()
        val state = AppState().also {
            it.autoCmds.add(command("含状态"))
            it.engineGlobals = mapOf("token" to JsonPrimitive("保留值"))
            it.engineGlobalMeta = mapOf("token" to GlobalVariableMeta("text", "变量备注"))
            it.logs.add(RunLog(901L, "含状态", "测试记录", "现在", true, Ic.Bolt, "日志详情"))
        }
        val target = AppState().also {
            it.oneTaps.add(command("替换目标", auto = false).copy(triggers = emptyList(), conditions = emptyList()))
        }
        val parsedTarget = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)

        val safety = restoreBackup(context, state, parsedTarget)
        val parsedSafety = parseImport(safety.readText(), state)

        assertEquals(ImportKind.BACKUP, parsedSafety.kind)
        assertEquals(ThemeMode.DARK, parsedSafety.preview.themeMode)
        assertEquals(JsonPrimitive("保留值"), parsedSafety.converted.engineGlobals["token"])
        assertEquals("变量备注", parsedSafety.converted.engineGlobalMeta["token"]?.note)
        assertEquals("日志详情", parsedSafety.converted.logs.single().detail)
        assertEquals("true", parsedSafety.settings?.appSettings?.get("keepalive.fgs"))
        assertFalse(parsedSafety.settings?.appSettings?.keys.orEmpty().any { "root" in it })
    }

    @Test
    fun safetyBackupDoesNotOverwriteAnEarlierBackupInTheSameSecond() {
        RuntimeEnvironment.getApplication()
        val state = AppState().also { it.autoCmds.add(command("恢复前的旧副本")) }
        val target = AppState().also {
            it.oneTaps.add(command("恢复目标", auto = false).copy(triggers = emptyList(), conditions = emptyList()))
        }
        val parsed = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)
        val folder = taskTempFolder("transfer-unique-safety")
        val fixedTime = { 1_800_000_000_000L }
        val ids = listOf("collision", "collision", "retry").iterator()
        val nextId = { ids.next() }

        val first = restoreBackup(state, parsed, folder, fixedTime, nextId)
        val firstBytes = first.readBytes()
        val second = restoreBackup(state, parsed, folder, fixedTime, nextId)

        assertNotEquals(first.canonicalPath, second.canonicalPath)
        assertTrue(firstBytes.contentEquals(first.readBytes()))
        assertEquals(2, folder.listFiles()?.count { it.extension == "json" })
    }

    @Test
    fun safetyBackupListContainsOnlyReadableFullBackups() {
        val state = AppState()
        val folder = taskTempFolder("transfer-safety-list")
        val valid = java.io.File(folder, "before-restore-20260101-010101-valid.json")
            .also { it.writeText(exportBackupJson(state, ThemeMode.DARK)) }
        java.io.File(folder, "before-restore-20260101-010102-broken.json").writeText("not json")
        java.io.File(folder, "before-restore-20260101-010103-command.json")
            .writeText(exportCommandJson(AppState(), command()))
        java.io.File(folder, "unrelated.json").writeText(valid.readText())
        java.io.File(folder, "before-restore-20260101-010104-directory.json").mkdir()
        val before = AppStateJson.encode(state.snapshot())

        assertEquals(listOf(valid), listSafetyBackups(folder, state))
        assertEquals(before, AppStateJson.encode(state.snapshot()))
    }

    @Test
    fun failedSafetyBackupReadbackLeavesStateAndEarlierCopiesUnchanged() {
        val state = AppState().also { it.autoCmds.add(command("原数据")) }
        val target = AppState().also {
            it.oneTaps.add(command("替换数据", auto = false).copy(triggers = emptyList(), conditions = emptyList()))
        }
        val parsed = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)
        val folder = taskTempFolder("transfer-readback-failure")
        val earlier = java.io.File(folder, "before-restore-earlier.json")
            .also { it.writeText(exportBackupJson(state, ThemeMode.DARK)) }
        val earlierBytes = earlier.readBytes()
        val before = AppStateJson.encode(state.snapshot())
        var readAttempted = false

        val failure = runCatching {
            restoreBackup(state, parsed, folder, readBack = { readAttempted = true; byteArrayOf(0) })
        }.exceptionOrNull()

        assertTrue("损坏的安全副本必须阻止恢复", failure is IllegalStateException)
        assertTrue(readAttempted)
        assertEquals(before, AppStateJson.encode(state.snapshot()))
        assertTrue(earlierBytes.contentEquals(earlier.readBytes()))
        assertEquals(listOf(earlier), folder.listFiles().orEmpty().toList())
    }

    @Test
    fun failedSafetyBackupReadKeepsTheOriginalStateAndCopies() {
        val state = AppState().also { it.autoCmds.add(command("原数据")) }
        val target = AppState().also {
            it.oneTaps.add(command("替换数据", auto = false).copy(triggers = emptyList(), conditions = emptyList()))
        }
        val parsed = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)
        val folder = taskTempFolder("transfer-read-error")
        val earlier = java.io.File(folder, "before-restore-earlier.json")
            .also { it.writeText(exportBackupJson(state, ThemeMode.DARK)) }
        val earlierBytes = earlier.readBytes()
        val before = AppStateJson.encode(state.snapshot())

        val failure = runCatching {
            restoreBackup(state, parsed, folder, readBack = { throw java.io.IOException("read failed") })
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertEquals(before, AppStateJson.encode(state.snapshot()))
        assertTrue(earlierBytes.contentEquals(earlier.readBytes()))
        assertEquals(listOf(earlier), folder.listFiles().orEmpty().toList())
    }

    @Test
    fun failedSafetyBackupWriteKeepsTheOriginalStateAndCopies() {
        val state = AppState().also { it.autoCmds.add(command("原数据")) }
        val target = AppState().also {
            it.oneTaps.add(command("替换数据", auto = false).copy(triggers = emptyList(), conditions = emptyList()))
        }
        val parsed = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)
        val folder = taskTempFolder("transfer-write-error")
        val occupied = java.io.File(folder, "occupied").also { it.writeText("keep") }
        val earlier = java.io.File(folder, "before-restore-earlier.json")
            .also { it.writeText(exportBackupJson(state, ThemeMode.DARK)) }
        val earlierBytes = earlier.readBytes()
        val before = AppStateJson.encode(state.snapshot())

        val failure = runCatching { restoreBackup(state, parsed, occupied) }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertEquals(before, AppStateJson.encode(state.snapshot()))
        assertTrue(earlierBytes.contentEquals(earlier.readBytes()))
        assertEquals("keep", occupied.readText())
    }

    @Test
    fun emptySingleCommandFileIsStillRejected() {
        val state = AppState()
        val text = exportBackupJson(state, ThemeMode.SYSTEM)
            .replace("\"flowglass.backup\"", "\"flowglass.command\"")

        val failure = runCatching { parseImport(text, state) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("文件不包含任何指令", failure?.message)
    }

}
