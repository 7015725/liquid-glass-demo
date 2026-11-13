package com.flowglass.demo.ui

import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyAliasCompatibilityTest {
    @get:Rule val folder = TemporaryFolder()

    // Exact v0.5 contract: 1b7d67d AppPersistence.migrateLegacySeedAction.
    // Titles are editable presentation, not an authority to grant an action type.
    private data class Alias(
        val commandId: Long, val title: String, val oldName: String,
        val name: String, val type: String, val parameterKey: String, val fallback: String,
    )

    private val aliases = listOf(
        Alias(1, "到公司自动连 WiFi", "打开 WiFi", "开关 WiFi", "action.wifi_switch", "mode", "开启"),
        Alias(3, "夜间自动静音", "开启勿扰", "开关勿扰", "action.dnd_switch", "mode", "开启"),
        Alias(3, "夜间自动静音", "勿扰模式", "开关勿扰", "action.dnd_switch", "mode", "开启"),
        Alias(3, "夜间自动静音", "亮度降到 10%", "调节亮度", "action.set_brightness", "value", "10%"),
        Alias(4, "低电量省电", "关闭蓝牙", "开关蓝牙", "action.bluetooth_switch", "mode", "关闭"),
        Alias(101, "一键省电", "亮度 30%", "调节亮度", "action.set_brightness", "value", "30%"),
        Alias(102, "一键勿扰", "勿扰模式", "开关勿扰", "action.dnd_switch", "mode", "开启"),
        Alias(102, "一键勿扰", "开启勿扰", "开关勿扰", "action.dnd_switch", "mode", "开启"),
        Alias(107, "执行脚本", "su -c 脚本", "执行 Shell(Root)", "action.root_shell", "command", "echo 流光"),
    )

    @Test fun everyV05AliasMapsWithItsExactCommandId() {
        aliases.forEach { assertMapped(it, it.title, "", it.fallback) }
    }

    @Test fun renamedV1SeedsKeepEveryAliasCapability() {
        aliases.forEach { assertMapped(it, "用户重命名的 ${it.commandId}", "", it.fallback) }
    }

    @Test fun editedParametersAreNeverReplacedByAliasDefaults() {
        aliases.forEach { alias ->
            val value = "用户参数 | 保留\n第二行"
            if (alias.type == "action.root_shell") assertMapped(alias, alias.title, value, value)
            else assertUnknown(load(alias.commandId, alias.title, alias.oldName, value), alias.oldName, value)
        }
    }

    @Test fun whitespaceParametersRemainByteForByteUnchanged() {
        aliases.forEach { alias ->
            listOf(" ", "\t\n", "\u3000").forEach { whitespace ->
                if (alias.type == "action.root_shell") assertMapped(alias, alias.title, whitespace, whitespace)
                else assertUnknown(load(alias.commandId, alias.title, alias.oldName, whitespace), alias.oldName, whitespace)
            }
        }
    }

    @Test fun aliasesOnEveryWrongSeedIdRemainUnknownEvenWithTheSeedTitle() {
        val commands = aliases.associate { it.commandId to it.title } + (9001L to "自定义指令")
        commands.forEach { (id, title) ->
            aliases.map { it.oldName }.distinct().forEach { name ->
                if (aliases.none { it.commandId == id && it.oldName == name }) {
                    listOf("", "用户文本", "\t ").forEach { parameter ->
                        assertUnknown(load(id, title, name, parameter), name, parameter)
                    }
                }
            }
        }
    }

    @Test fun rootAliasOnWifiSeedNeverGrantsRootCapability() {
        val saved = load(1, "到公司自动连 WiFi", "su -c 脚本", "echo user-data")
        assertUnknown(saved, "su -c 脚本", "echo user-data")
    }

    @Test fun nearMatchAliasNamesAreNotNormalizedIntoCapabilities() {
        aliases.forEach { alias ->
            listOf(" ${alias.oldName}", "${alias.oldName} ", "${alias.oldName}\n").forEach { name ->
                assertUnknown(load(alias.commandId, alias.title, name, ""), name, "")
            }
        }
    }

    @Test fun oldVersion2AliasTextNeverReceivesV1CapabilitiesOrDefaults() {
        aliases.forEach { alias ->
            listOf(alias.title, "改名的旧 version 2 指令").forEach { title ->
                listOf("", "用户参数", "\t\n").forEach { parameter ->
                    assertUnknown(load(alias.commandId, title, alias.oldName, parameter, version = 2),
                        alias.oldName, parameter)
                }
            }
        }
    }

    @Test fun canonicalActionWithEmptyParameterDoesNotReceiveAnAliasDefault() {
        aliases.forEach { alias ->
            val saved = load(alias.commandId, alias.title, alias.name, "")
            if (alias.type == "action.root_shell") {
                assertEquals(alias.type, saved.engineRules.single().actions.single().type)
                assertEquals(JsonPrimitive(""), saved.engineRules.single().actions.single().params[alias.parameterKey])
            } else assertUnknown(saved, alias.name, "")
            assertEquals("", commands(saved).single().actions.single().parameter)
        }
    }

    private fun assertMapped(alias: Alias, title: String, input: String, expected: String) {
        val saved = load(alias.commandId, title, alias.oldName, input)
        val command = commands(saved).single()
        val action = command.actions.single()
        val typed = saved.engineRules.single().actions.single()
        val context = "${alias.commandId} / ${alias.oldName} / $title"
        assertEquals(context, title, command.title)
        assertEquals(context, alias.name, action.name)
        assertEquals(context, alias.type, action.typeId)
        assertEquals(context, expected, action.parameter)
        assertEquals(context, alias.type, typed.type)
        val canonical = when (alias.parameterKey) {
            "mode" -> JsonPrimitive(if (expected == "开启") "on" else "off")
            "value" -> JsonPrimitive(expected.removeSuffix("%").toInt())
            else -> JsonPrimitive(expected)
        }
        assertEquals(context, canonical, typed.params[alias.parameterKey])
    }

    private fun assertUnknown(saved: PersistedAppState, name: String, parameter: String) {
        val action = commands(saved).single().actions.single()
        val typed = saved.engineRules.single().actions.single()
        assertEquals("${commands(saved).single().id} / $name", "legacy.unknown", action.typeId)
        assertEquals(name, action.name)
        assertEquals(parameter, action.parameter)
        assertEquals("legacy.unknown", typed.type)
        assertEquals(JsonPrimitive(name), typed.params["name"])
        assertEquals(JsonPrimitive(parameter), typed.params["parameter"])
        assertEquals(JsonPrimitive("$name：$parameter"), typed.params["raw"])
    }

    private fun commands(saved: PersistedAppState) = saved.autoCmds + saved.oneTaps

    private fun load(id: Long, title: String, name: String, parameter: String, version: Int = 1): PersistedAppState {
        val auto = id < 100
        val item = JSONObject().put("id", 100_001).put("ic", "Bolt")
            .put("name", name).put("parameter", parameter)
        val command = JSONObject().put("id", id).put("auto", auto).put("ic", "Bolt")
            .put("title", title).put("desc", "保留说明")
            .put("triggers", JSONArray()).put("conditions", JSONArray()).put("actions", JSONArray().put(item))
            .put("category", 0).put("enabled", true).put("pinned", false)
            .put("lastRun", "从未").put("descriptionEdited", true)
        val source = JSONObject().put("version", version)
            .put("autoCmds", if (auto) JSONArray().put(command) else JSONArray())
            .put("oneTaps", if (auto) JSONArray() else JSONArray().put(command))
            .put("logs", JSONArray()).put("running", true).put("mode", 1).toString()
        val file = File(folder.newFolder(), "commands.json").also { it.writeText(source) }
        val loaded = CommandStore(file).load()
        assertNull(loaded.error)
        val saved = requireNotNull(loaded.saved)
        assertEquals(source, File(file.parentFile, "commands.v1.bak.json").readText())
        assertEquals(2, JSONObject(file.readText()).getInt("formatVersion"))
        val reopened = CommandStore(file).load()
        assertNull(reopened.error)
        assertEquals(saved, reopened.saved)
        assertTrue(file.isFile)
        return saved
    }
}
