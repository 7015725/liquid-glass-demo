package com.flowglass.demo.ui

import com.flowglass.demo.system.exec.StepResult
import com.flowglass.demo.system.exec.StepStatus
import com.flowglass.engine.LegacyCommandMigration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyLogIdMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "commands.json")
    private fun fixture() = requireNotNull(javaClass.getResource("/legacy-v04-seed-logs.json")).readText()

    @Test fun v04OriginalFourSeedLogsMigrateWithoutLosingCommandsOrRecords() {
        val source = fixture()
        val expectedLogs = listOf(
            RunLog(-1, "验证码自动复制", "通知触发", "21:47", true, Ic.Bell),
            RunLog(-2, "一键省电", "手动执行", "20:15", true, Ic.Leaf),
            RunLog(-3, "断网自动重连", "WiFi 断开", "18:02", false, Ic.Wifi),
            RunLog(-4, "到公司自动连 WiFi", "进入地点", "08:56", true, Ic.Location),
        )
        val expectedCommands = commandsWithoutLogs(source)
        val saved = migrateAndReopen(source)
        assertEquals(listOf(1003L, 1004L, 1005L, 1006L), saved.logs.map { it.id })
        assertEquals(expectedLogs, saved.logs.mapIndexed { index, log -> log.copy(id = expectedLogs[index].id) })
        assertEquals(expectedCommands.autoCmds, saved.autoCmds)
        assertEquals(expectedCommands.oneTaps, saved.oneTaps)
        assertEquals(expectedCommands.engineRules, saved.engineRules)
        assertEquals(expectedCommands.engineUi.presentation, saved.engineUi.presentation)
        assertUniquePositiveIds(saved)
    }

    @Test fun canonicalIdValidationReportsTheDeclarationCategory() {
        val base = LegacyCommandMigration.migrate(JSONObject(fixture()).put("logs", org.json.JSONArray()).toString())
        val ruleId = base.rules.first().id
        val sidecar = base.ui.presentation.getValue(ruleId)
        fun rejected(document: com.flowglass.engine.RuleDocument, category: String) {
            val failure = runCatching { document.toPersistedAppState() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure?.message, failure?.message.orEmpty().contains(category))
        }
        rejected(base.copy(ui = base.ui.copy(presentation = base.ui.presentation +
            (ruleId to sidecar.copy(legacyId = 0)))), "指令 id")
        rejected(base.copy(ui = base.ui.copy(presentation = base.ui.presentation +
            (ruleId to sidecar.copy(triggers = listOf(sidecar.triggers.single().copy(id = 0)))))), "条目 id")
        rejected(base.copy(ui = base.ui.copy(logs = listOf(
            com.flowglass.engine.PersistedLog(0, "日志", "手动", "12:00", true, "Bell"),
        ))), "执行记录 id")
    }

    @Test fun v05ReleasedEncoderPreservesDetailAndStepsIncludingDeletedItemReferences() {
        val originalCommands = commandsWithoutLogs(fixture())
        val logs = listOf(
            RunLog(0, "执行结果", "手动执行", "12:34", false, Ic.Tap, "两条结果\n保留 | 原文", listOf(
                StepResult(1002, "调节亮度", StepStatus.SUCCESS, "已完成", 7),
                StepResult(9000, "已删除的任务", StepStatus.FAILURE, "失败详情\n保留", 123),
                StepResult(9000, "重复历史引用", StepStatus.SKIPPED, "前一步失败", 0),
            )),
            RunLog(8000, "正常日志", "手动执行", "11:00", true, Ic.Bell, "保持原 id"),
        )
        val input = PersistedAppState(originalCommands.autoCmds, originalCommands.oneTaps, logs, false, 2)
        val source = V05StateEncoder.encode(input)
        assertEquals(2, JSONObject(source).getInt("version"))
        assertFalse(JSONObject(source).has("formatVersion"))
        val saved = migrateAndReopen(source)
        assertEquals(listOf(9001L, 8000L), saved.logs.map { it.id })
        assertEquals(logs, saved.logs.mapIndexed { index, log -> log.copy(id = logs[index].id) })
        assertEquals(input.autoCmds, saved.autoCmds)
        assertEquals(input.oneTaps, saved.oneTaps)
        assertFalse(saved.running)
        assertEquals(2, saved.mode)
        assertUniquePositiveIds(saved)
    }

    private fun commandsWithoutLogs(source: String) = AppStateJson.decode(
        JSONObject(source).put("logs", org.json.JSONArray()).toString(),
    )

    private fun migrateAndReopen(source: String): PersistedAppState {
        file.writeText(source)
        val store = CommandStore(file)
        val loaded = store.load()
        assertNull(loaded.error)
        assertNotNull(loaded.saved)
        assertTrue(store.canSave)
        val saved = requireNotNull(loaded.saved)
        assertEquals(source, File(folder.root, "commands.v1.bak.json").readText())
        assertEquals(2, JSONObject(file.readText()).getInt("formatVersion"))
        // Exercise the UI projection, save, and reload so a second strict-ID check cannot block recovery.
        store.save(loaded.appState().snapshot())
        val reloaded = CommandStore(file).load()
        assertNull(reloaded.error)
        assertEquals(saved, reloaded.saved)
        return requireNotNull(reloaded.saved)
    }

    private fun assertUniquePositiveIds(saved: PersistedAppState) {
        val ids = (saved.autoCmds + saved.oneTaps).flatMap {
            listOf(it.id) + (it.triggers + it.conditions + it.actions).map { item -> item.id }
        } + saved.logs.map { it.id }
        assertTrue(ids.all { it > 0L })
        assertEquals(ids.size, ids.distinct().size)
    }
}
