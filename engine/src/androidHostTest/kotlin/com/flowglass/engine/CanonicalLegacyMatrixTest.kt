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
        // Golden values transcribed from v0.5.0 CommandCatalog (main@1b7d67d), plus the 1b-1
        // trigger defaults appended in registry order.
        val texts = listOf("08:00|每天", "", "", "", "", "", "低于|20%", "",
            "20%", "开始充电", "公司", "家", "验证码", "耳机", "连接家庭网络", "应用包名",
            "08:00–22:00", "工作日", "20%–80%", "充电中", "已亮屏", "WiFi", "已关闭",
            "开启", "开启", "开启", "50%", "50%", "应用包名", "任务已完成", "待复制文本", "1 秒", "echo 流光", "GET https://example.com")
        assertEquals(texts.size, BuiltInComponents.all.size)
        for ((meta, text) in BuiltInComponents.all.zip(texts)) for (version in listOf(1, 2)) {
            val component = migrate(meta, text, version)
            assertEquals(meta.typeId, component.type, meta.name)
            assertEquals(meta.params.associate { it.key to it.defaultValue }, component.params, meta.name)
            val formatted = ParameterCodec.formatComponent(meta, component.params)
            assertEquals(if (meta.typeId == "action.post_notification") "|$text" else text, formatted, meta.name)
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
