package com.flowglass.engine

import kotlinx.serialization.json.*
import kotlin.test.*

class CanonicalLegacyMatrixTest {
    private fun migrate(meta: ComponentMeta, parameter: String, version: Int = 1): RuleComponent {
        val item = buildJsonObject { put("id", 2); put("name", meta.name); put("parameter", parameter); put("ic", "Bolt") }
        val source = buildJsonObject {
            put("version", version)
            put("autoCmds", buildJsonArray { add(buildJsonObject {
                put("id", 1); put("auto", true); put("ic", "Bolt"); put("title", "用户标题"); put("desc", "")
                put(when (meta.kind) { ComponentKind.TRIGGER -> "triggers"; ComponentKind.CONDITION -> "conditions"; ComponentKind.ACTION -> "actions" }, buildJsonArray { add(item) })
            }) })
        }
        val document = LegacyCommandMigration.migrate(source.toString())
        assertEquals(document, RuleJson.decode(RuleJson.encode(document)))
        val rule = document.rules.single()
        return (rule.triggers + rule.conditions + rule.actions).single()
    }

    @Test fun everyBuiltInMigratesItsV05DefaultThroughRealLegacyDocument() {
        // Golden values transcribed from v0.5.0 CommandCatalog (main@1b7d67d), the 1b-1
        // triggers and the 1b-2 additions. Keyed by type id so registry order is not part of
        // the contract; every built-in must have an entry and no stale entries may remain.
        val expected = mapOf(
            "trigger.time" to "08:00|每天", "trigger.screen_on" to "", "trigger.screen_off" to "",
            "trigger.user_present" to "", "trigger.power_connected" to "",
            "trigger.power_disconnected" to "", "trigger.battery_level" to "低于|20%",
            "trigger.boot_completed" to "", "trigger.battery_low" to "20%",
            "trigger.charging" to "开始充电",
            "trigger.wifi" to "已连接|", "trigger.bluetooth" to "已连接|",
            "trigger.notification" to "||", "trigger.app_foreground" to "切到前台|应用包名",
            "trigger.headset" to "已插入|任意耳机",
            "trigger.location_enter" to "公司", "trigger.location_exit" to "家",
            "condition.time_range" to "08:00–22:00", "condition.weekday" to "工作日",
            "condition.battery_range" to "20%–80%", "condition.charging" to "充电中",
            "condition.screen_state" to "已亮屏", "condition.network_type" to "WiFi",
            "condition.dnd_state" to "已关闭",
            "action.wifi_switch" to "开启", "action.bluetooth_switch" to "开启",
            "action.dnd_switch" to "开启", "action.set_brightness" to "50%",
            "action.set_volume" to "50%", "action.launch_app" to "应用包名",
            "action.post_notification" to "任务已完成", "action.copy_clipboard" to "待复制文本",
            "action.delay" to "1 秒", "action.root_shell" to "echo 流光",
            "action.http_request" to "GET https://example.com",
            "action.vibrate" to "500 毫秒", "action.flashlight" to "切换",
            "action.ringer_mode" to "响铃", "action.media_control" to "播放",
            "action.open_url" to "https://example.com", "action.speak" to "你好，流光",
            "action.toast" to "任务已完成", "action.screen_timeout" to "30 秒",
            "action.auto_rotate" to "开启", "action.global_action" to "主页",
            "action.airplane_mode" to "开启", "action.mobile_data" to "开启",
        )
        assertEquals(expected.keys, BuiltInComponents.all.map { it.typeId }.toSet())
        for (meta in BuiltInComponents.all) {
            val text = expected.getValue(meta.typeId)
            for (version in listOf(1, 2)) {
                val component = migrate(meta, text, version)
                assertEquals(meta.typeId, component.type, meta.name)
                assertEquals(meta.params.associate { it.key to it.defaultValue }, component.params, meta.name)
                val formatted = ParameterCodec.formatComponent(meta, component.params)
                assertEquals(if (meta.typeId == "action.post_notification") "|$text" else text, formatted, meta.name)
            }
        }
    }

    @Test fun everyConstrainedBuiltinPreservesUnparseableTextAsWholeUnknownItem() {
        val constrained = BuiltInComponents.all.filter { meta -> meta.params.any { it.type !in setOf(ParamType.TEXT, ParamType.MULTILINE_TEXT, ParamType.APPLICATION) } }
        assertTrue(constrained.isNotEmpty())
        for (meta in constrained) for (invalid in listOf("", "\t\n", "错误 | 保留\n原文")) {
            val component = migrate(meta, invalid)
            assertEquals("legacy.unknown", component.type, meta.name)
            assertEquals(mapOf("name" to JsonPrimitive(meta.name), "parameter" to JsonPrimitive(invalid),
                "icon" to JsonPrimitive("Bolt"), "raw" to JsonPrimitive("${meta.name}：$invalid")), component.params)
        }
    }

    @Test fun freeTextAndApplicationPreserveArbitraryTextIncludingEmptyAndWhitespace() {
        val metas = BuiltInComponents.all.filter { it.params.size == 1 && it.params.single().type in setOf(ParamType.TEXT, ParamType.MULTILINE_TEXT, ParamType.APPLICATION) }
        assertTrue(metas.isNotEmpty())
        for (meta in metas) for (text in listOf("", "\t\n", "引号\" | JSON { } 中文\n第二行")) {
            val component = migrate(meta, text)
            assertEquals(meta.typeId, component.type)
            assertEquals(JsonPrimitive(text), component.params[meta.params.single().key])
        }
    }

    @Test fun rangeAndDurationMigrationDoNotSilentlyClampOrLosePrecision() {
        val delay = BuiltInComponents.all.single { it.typeId == "action.delay" }
        assertEquals(JsonPrimitive(1200000), migrate(delay, "20 分钟").params["duration"])
        assertEquals("legacy.unknown", migrate(delay, "0.0001 秒").type)
        val battery = BuiltInComponents.all.single { it.typeId == "condition.battery_range" }
        assertEquals("legacy.unknown", migrate(battery, "80%–20%").type)
        assertEquals("legacy.unknown", migrate(battery, "20%–120%").type)
        val clock = BuiltInComponents.all.single { it.typeId == "condition.time_range" }
        assertEquals(RuleJson.format.parseToJsonElement("""{"from":"22:00","to":"07:00"}"""), migrate(clock, "22:00–07:00").params["range"])
    }

    @Test fun allEnumLabelsAndCanonicalTokensMigrateToValues() {
        for (meta in BuiltInComponents.all.filter { it.params.singleOrNull()?.type in setOf(ParamType.TRI_STATE, ParamType.ENUM) }) {
            val spec = meta.params.single()
            for (option in spec.options) for (input in listOf(option.label, option.value)) {
                assertEquals(JsonPrimitive(option.value), migrate(meta, input).params[spec.key])
            }
        }
    }

    @Test fun notificationKeepsBothFieldsAndAllBodySeparators() {
        val meta = BuiltInComponents.all.single { it.typeId == "action.post_notification" }
        assertEquals(mapOf("title" to JsonPrimitive("标题"), "content" to JsonPrimitive("内容|第二段\n末行")),
            migrate(meta, "标题|内容|第二段\n末行").params)
    }

    @Test fun noParameterComponentCannotSilentlyDiscardUnexpectedText() {
        val meta = BuiltInComponents.all.single { it.typeId == "trigger.boot_completed" }
        assertEquals("legacy.unknown", migrate(meta, "保留文字").type)
    }
}
