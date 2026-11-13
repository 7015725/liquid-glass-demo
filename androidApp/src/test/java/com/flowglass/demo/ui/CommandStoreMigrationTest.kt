package com.flowglass.demo.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommandStoreMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "commands.json")
    private val backup get() = File(folder.root, "commands.v1.bak.json")

    @Test fun v1SamplesPreserveEditedInstructionAndUnmappedAction() {
        val source = legacyDocument(
            commands = listOf(
                legacyCommand(
                    id = 1,
                    auto = true,
                    title = "用户改过的标题",
                    description = "用户改过的说明",
                    actions = listOf(
                        legacyItem(2, "调节亮度", "37%"),
                        legacyItem(3, "自建任务", "原始参数 | 保留"),
                    ),
                ),
            ),
        )
        file.writeText(source)

        val migrated = requireNotNull(CommandStore(file).load().saved)
        val rule = migrated.engineRules.single()

        assertEquals("用户改过的标题", rule.name)
        assertEquals("用户改过的说明", rule.description)
        assertEquals("action.set_brightness", rule.actions[0].type)
        assertEquals("37%", rule.actions[0].params.getValue("value").toString().trim('"'))
        assertEquals("legacy.unknown", rule.actions[1].type)
        assertEquals("自建任务：原始参数 | 保留", rule.actions[1].params.getValue("raw").toString().trim('"'))
    }

    @Test fun v1SeedExamplesMapToTypedActionsAndKeepAnEditedParameter() {
        file.writeText(legacyDocument(commands = listOf(
            legacyCommand(
                id = 1,
                auto = true,
                title = "改名后的公司指令",
                actions = listOf(legacyItem(2, "打开 WiFi", "")),
            ),
            legacyCommand(
                id = 101,
                auto = false,
                title = "改名后的一键省电",
                actions = listOf(legacyItem(102, "亮度 30%", "37%")),
            ),
        )))

        val migrated = requireNotNull(CommandStore(file).load().saved)

        assertEquals("改名后的公司指令", migrated.autoCmds.single().title)
        assertEquals("改名后的一键省电", migrated.oneTaps.single().title)
        assertEquals("action.wifi_switch", migrated.autoCmds.single().let { it.actions.single().typeId })
        assertEquals("开关 WiFi", migrated.autoCmds.single().actions.single().name)
        assertEquals("开启", migrated.autoCmds.single().actions.single().parameter)
        assertEquals("action.set_brightness", migrated.oneTaps.single().actions.single().typeId)
        assertEquals("37%", migrated.oneTaps.single().actions.single().parameter)
    }

    @Test fun aliasNamedCustomActionIsNotRelabeledAsABuiltInAction() {
        file.writeText(legacyDocument(commands = listOf(
            legacyCommand(
                id = 9_001,
                auto = false,
                title = "自定义旧指令",
                actions = listOf(legacyItem(9_002, "打开 WiFi", "仅作自定义文本")),
            ),
        )))

        val action = requireNotNull(CommandStore(file).load().saved).oneTaps.single().actions.single()

        assertEquals("legacy.unknown", action.typeId)
        assertEquals("打开 WiFi", action.name)
        assertEquals("仅作自定义文本", action.parameter)
    }

    @Test fun emptyLegacyParametersRemainEmpty() {
        file.writeText(legacyDocument(commands = listOf(
            legacyCommand(10, auto = false, actions = listOf(legacyItem(11, "延时等待", ""))),
        )))

        val action = requireNotNull(CommandStore(file).load().saved).engineRules.single().actions.single()

        assertEquals("action.delay", action.type)
        assertEquals("\"\"", action.params.getValue("duration").toString())
    }

    @Test fun v2FileIsLoadedWithoutRemigrationOrBackup() {
        val state = AppState().snapshot()
        val v2 = AppStateJson.encode(state)
        // Current formatVersion=2 is not the old version=2 name-based schema.
        assertEquals(2, JSONObject(v2).getInt("formatVersion"))
        file.writeText(v2)

        val loaded = CommandStore(file).load()

        assertNotNull(loaded.saved)
        assertEquals(v2, file.readText())
        assertFalse(backup.exists())
    }

    @Test fun originalBytesAreBackedUpBeforeV1Replacement() {
        val source = legacyDocument(commands = listOf(legacyCommand(20, auto = false)))
        val original = ("\n$source\n").toByteArray(Charsets.UTF_8)
        file.writeBytes(original)

        val loaded = CommandStore(file).load()

        assertNotNull(loaded.saved)
        assertTrue(backup.isFile)
        assertTrue(original.contentEquals(backup.readBytes()))
        assertEquals(2, JSONObject(file.readText()).getInt("formatVersion"))
    }

    @Test fun failedAtomicMigrationPreservesOriginalAndBlocksLaterWrites() {
        val source = legacyDocument(commands = listOf(legacyCommand(30, auto = false)))
        val original = source.toByteArray(Charsets.UTF_8)
        file.writeBytes(original)
        val obstruction = File(file.path + ".new")
        assertTrue(obstruction.mkdir())
        File(obstruction, "occupied").writeText("keep")
        assertTrue(File(obstruction, "occupied").isFile)

        val store = CommandStore(file)
        val result = store.load()

        assertNotNull(result.error)
        assertTrue(original.contentEquals(android.util.AtomicFile(file).openRead().use { it.readBytes() }))
        assertFalse(store.canSave)
        assertTrue(runCatching { store.save(AppState().snapshot()) }.isFailure)
    }

    private fun legacyDocument(commands: List<JSONObject>): String = JSONObject()
        .put("version", 1)
        .put("autoCmds", JSONArray(commands.filter { it.getBoolean("auto") }))
        .put("oneTaps", JSONArray(commands.filterNot { it.getBoolean("auto") }))
        .put("logs", JSONArray())
        .put("running", true)
        .put("mode", 1)
        .toString()

    private fun legacyCommand(
        id: Long,
        auto: Boolean,
        title: String = "样例指令",
        description: String = "",
        actions: List<JSONObject> = emptyList(),
    ) = JSONObject()
        .put("id", id)
        .put("auto", auto)
        .put("ic", "Bolt")
        .put("title", title)
        .put("desc", description)
        .put("triggers", JSONArray())
        .put("conditions", JSONArray())
        .put("actions", JSONArray(actions))

    private fun legacyItem(id: Long, name: String, parameter: String) = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("parameter", parameter)
        .put("ic", "Bolt")
}