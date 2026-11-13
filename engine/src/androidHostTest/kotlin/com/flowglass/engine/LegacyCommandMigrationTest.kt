package com.flowglass.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class LegacyCommandMigrationTest {
    @Test
    fun migratesKnownAndUnknownLegacyActionsIntoTypedRuleComponents() {
        val legacy = """
            {
              "version": 2,
              "autoCmds": [{
                "id": 1,
                "auto": true,
                "title": "夜间自动静音",
                "desc": "保留描述",
                "ic": "Moon",
                "triggers": [{"id": 11, "name": "时间", "parameter": "23:00", "ic": "Clock"}],
                "conditions": [],
                "actions": [
                  {"id": 21, "name": "调节亮度", "parameter": "10%", "ic": "Gauge"},
                  {"id": 22, "name": "未知动作", "parameter": "原样参数", "ic": "Bolt"}
                ],
                "category": 2,
                "enabled": true,
                "pinned": true,
                "lastRun": "昨天",
                "descriptionEdited": true
              }],
              "oneTaps": [], "logs": [], "running": true, "mode": 1
            }
        """.trimIndent()

        val document = LegacyCommandMigration.migrate(legacy)
        val rule = document.rules.single()

        assertEquals(2, document.formatVersion)
        assertEquals("auto", rule.type)
        assertEquals("夜间自动静音", rule.name)
        assertEquals("保留描述", rule.description)
        assertEquals("action.set_brightness", rule.actions[0].type)
        assertEquals("10%", rule.actions[0].params["value"].toString().trim('"'))
        assertEquals("legacy.unknown", rule.actions[1].type)
        assertTrue(rule.actions[1].params["raw"].toString().contains("未知动作"))
        assertTrue(rule.enabled)
        assertEquals("drop", rule.runMode)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(rule.id))
        assertTrue(rule.id != LegacyCommandMigration.uuidForLegacyId(2L))
    }

    @Test
    fun notificationTitleAndBodyMigrateIntoSeparateParameters() {
        val legacy = """{"version":2,"autoCmds":[],"oneTaps":[{"id":101,"auto":false,"ic":"Bell","title":"测试","desc":"","actions":[{"id":102,"name":"发送通知","parameter":"标题|正文|保留","ic":"Bell"}]}],"logs":[],"running":true,"mode":1}"""
        val rule = LegacyCommandMigration.migrate(legacy).rules.single()
        assertEquals("标题", (rule.actions.single().params["title"] as JsonPrimitive).content)
        assertEquals("正文|保留", (rule.actions.single().params["content"] as JsonPrimitive).content)
    }

    @Test
    fun rejectsMalformedLegacyFilesWithoutProducingPartialRules() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            LegacyCommandMigration.migrate("{\"version\":2,\"autoCmds\":[{\"id\":\"bad\"}]}")
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            LegacyCommandMigration.migrate("{\"version\":8,\"autoCmds\":[],\"oneTaps\":[]}")
        }
    }
}
