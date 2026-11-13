package com.flowglass.demo.ui

import com.flowglass.engine.RuleJson
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
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
class CanonicalStorageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun editorWritesNumericDurationAndOnlyIdentityPresentation() {
        val file = File(folder.root, "commands.json")
        val store = CommandStore(file)
        val state = store.load().appState()
        val command = state.create(false)
        val option = CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.delay" }
        state.addItem(command.id, CommandSection.ACTIONS, option)
        val item = requireNotNull(state.find(command.id)).actions.single()
        state.setItemParameter(command.id, CommandSection.ACTIONS, item.id, "1.5 秒")
        store.save(state.snapshot())
        val disk = RuleJson.decode(file.readText())
        val rule = disk.rules.single { disk.ui.presentation[it.id]?.legacyId == command.id }
        assertEquals(JsonPrimitive(1500), rule.actions.single().params["duration"])
        val presentation = JSONObject(file.readText()).getJSONObject("ui").getJSONObject("presentation")
            .getJSONObject(rule.id).getJSONArray("actions").getJSONObject(0)
        assertEquals(setOf("id", "icon"), presentation.keys().asSequence().toSet())
        val loaded = CommandStore(file).load()
        assertNull(loaded.error)
        val reopened = loaded.appState()
        assertEquals("1500 毫秒", reopened.find(command.id)!!.actions.single().parameter)
        val bytes = file.readBytes()
        CommandStore(file).also { it.load(); it.save(reopened.snapshot()) }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun malformedLegacyParameterIsPreservedAsUnknownAndBackedUp() {
        val item = JSONObject().put("id", 2).put("name", "延时等待")
            .put("parameter", "不是时间\n保留 | 原文").put("ic", "Timer")
        val command = JSONObject().put("id", 1).put("auto", false).put("ic", "Bolt")
            .put("title", "迁移样例").put("desc", "")
            .put("actions", JSONArray().put(item))
        val source = JSONObject().put("version", 1).put("oneTaps", JSONArray().put(command)).toString()
        val file = File(folder.root, "commands.json").apply { writeText(source) }
        val store = CommandStore(file)
        val loaded = store.load()
        assertNull(loaded.error)
        val component = requireNotNull(loaded.saved).engineRules.single().actions.single()
        assertEquals("legacy.unknown", component.type)
        assertEquals(JsonPrimitive(item.getString("parameter")), component.params["parameter"])
        assertEquals(JsonPrimitive("延时等待"), component.params["name"])
        assertEquals(source, File(folder.root, "commands.v1.bak.json").readText())
        store.save(loaded.appState().snapshot())
        assertEquals(component, RuleJson.decode(file.readText()).rules.single().actions.single())
    }

    @Test fun deletedHistoricalReferenceIsReservedEvenWhenAllSidecarsExist() {
        val historical = newId() + 10_000
        val source = JSONObject().put("version", 1).put("oneTaps", JSONArray().put(
            JSONObject().put("id", 1).put("auto", false).put("ic", "Bolt")
                .put("title", "引用").put("desc", "").put("actions", JSONArray().put(
                    JSONObject().put("id", 2).put("name", "复制到剪贴板").put("parameter", "x").put("ic", "Copy")
                ))
        )).put("logs", JSONArray().put(JSONObject().put("id", 3).put("title", "历史")
            .put("reason", "手动").put("time", "12:00").put("ok", true).put("ic", "Bolt")
            .put("steps", JSONArray().put(JSONObject().put("itemId", historical).put("name", "已删除")
                .put("status", "SUCCESS").put("detail", "完成").put("durationMs", 1)))))
        val file = File(folder.root, "commands.json").apply { writeText(source.toString()) }
        val state = CommandStore(file).load().appState()
        assertTrue(state.create(false).id > historical)
    }
}
