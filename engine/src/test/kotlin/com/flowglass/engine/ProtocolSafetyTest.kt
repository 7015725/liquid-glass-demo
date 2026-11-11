package com.flowglass.engine

import org.junit.Assert.*
import org.junit.Test

class ProtocolSafetyTest {
    private val valid = """{"name":"ok","type":"manual","actions":[{"type":"action.stop"}]}"""
    @Test fun importLimitUsesUtf8BytesWithInclusiveOneMiBBoundary() {
        val exact = valid + " ".repeat(1048576 - valid.toByteArray().size)
        assertEquals("ok", RuleCodec.decodeBundle(exact).rules.single().name)
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeBundle(exact + " ") }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeBundle(valid.replace("ok", "中".repeat(350000))) }
    }
    @Test fun duplicateJsonKeysIncludingEscapedAliasesAreRejected() {
        listOf(
            valid.replace("\"name\":\"ok\"", "\"name\":\"first\",\"name\":\"ok\""),
            valid.replace("\"name\":\"ok\"", "\"name\":\"first\",\"n\\u0061me\":\"ok\""),
            valid.replace("\"action.stop\"", "\"action.stop\",\"params\":{\"x\":1,\"x\":2}")
        ).forEach { text ->
            val e = assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeBundle(text) }
            assertTrue(e.message, e.message!!.contains("重复"))
        }
    }
    @Test fun typedProtocolScalarsCannotCoerceStringsOrOverflow() {
        listOf("\"debounceMillis\":\"1\"", "\"schemaVersion\":\"1\"", "\"enabled\":\"true\"", "\"debounceMillis\":9223372036854775808").forEach { field ->
            assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeBundle(valid.replace("\"name\"", "$field,\"name\"")) }
        }
    }
    @Test fun deeplyNestedJsonIsRejectedBeforeDeserialization() {
        val text = "[".repeat(150) + "0" + "]".repeat(150)
        val error = assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeBundle(text) }
        assertTrue(error.message, error.message!!.contains("嵌套"))
    }
    @Test fun unknownFieldsAndMalformedSyntaxDoNotDisappear() {
        listOf(valid.replace("\"name\"", "\"future\":42,\"name\""), valid + "{}", valid.dropLast(1), "{name:\"ok\"}", valid.replace("\"ok\"", "NaN")).forEach {
            assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeBundle(it) }
        }
    }
}
