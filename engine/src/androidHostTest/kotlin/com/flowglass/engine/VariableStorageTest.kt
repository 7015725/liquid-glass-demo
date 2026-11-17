package com.flowglass.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class VariableStorageTest {
    @Test
    fun v3DocumentMigratesToV4WithDefaultNotes() {
        val v3 = """
            {"formatVersion":3,"rules":[
              {"id":"r","locals":[{"name":"count","type":"int","initial":1}]}
            ],"globals":{"greeting":"hello"}}
        """.trimIndent()
        val decoded = RuleJson.decodeWithMigration(v3)
        assertEquals(3, decoded.migratedFrom)
        assertEquals(CURRENT_FORMAT_VERSION, decoded.document.formatVersion)
        assertEquals("", decoded.document.rules.single().locals.single().note)
        assertEquals(GlobalVariableMeta(), decoded.document.globalMeta["greeting"])
    }

    @Test
    fun notesAndGlobalMetaRoundTrip() {
        val document = RuleDocument(
            rules = listOf(
                Rule(
                    id = "r",
                    locals = listOf(
                        LocalVariable(
                            name = "count",
                            type = "number",
                            initial = kotlinx.serialization.json.JsonPrimitive(1),
                            note = "累计次数",
                        ),
                    ),
                ),
            ),
            globals = mapOf("token" to kotlinx.serialization.json.JsonPrimitive("secret")),
            globalMeta = mapOf("token" to GlobalVariableMeta("text", "接口令牌")),
        )
        val decoded = RuleJson.decode(RuleJson.encode(document))
        assertEquals("累计次数", decoded.rules.single().locals.single().note)
        assertEquals(GlobalVariableMeta("text", "接口令牌"), decoded.globalMeta["token"])
    }
}
