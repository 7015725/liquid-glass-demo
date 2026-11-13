package com.flowglass.engine

import kotlinx.serialization.json.*
import kotlin.test.*

class CanonicalMigrationTest {
    @Test fun legacySwitchAndPercentageBecomeCanonicalValues() {
        val source = """{"version":1,"oneTaps":[{"id":1,"auto":false,"ic":"Bolt","title":"测试","desc":"","actions":[{"id":2,"ic":"Wifi","name":"开关 WiFi","parameter":"开启"},{"id":3,"ic":"Gauge","name":"调节亮度","parameter":"50%"}]}]}"""
        val migrated = LegacyCommandMigration.migrate(source)
        assertEquals(JsonPrimitive("on"), migrated.rules.single().actions[0].params["mode"])
        assertEquals(JsonPrimitive(50), migrated.rules.single().actions[1].params["value"])
        val presentation = RuleJson.format.parseToJsonElement(RuleJson.encode(migrated)).jsonObject
            .getValue("ui").jsonObject.getValue("presentation").jsonObject.values.single().jsonObject
            .getValue("actions").jsonArray.first().jsonObject
        assertEquals(setOf("id", "icon"), presentation.keys)
    }
}
