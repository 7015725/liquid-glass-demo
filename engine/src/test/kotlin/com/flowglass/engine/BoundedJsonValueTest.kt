package com.flowglass.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BoundedJsonValueTest {
    @Test fun deepUntrustedValueIsRejectedBeforeRecursiveParsing() {
        val raw = "[".repeat(10_000) + "0" + "]".repeat(10_000)
        assertTrue(raw.toByteArray(Charsets.UTF_8).size < EngineLimits.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeJsonValue(raw) }
    }

    @Test fun valueDepthBoundaryAndNumericLexemesRemainIntact() {
        val boundary = "[".repeat(EngineLimits.MAX_VALUE_DEPTH - 1) + "0" + "]".repeat(EngineLimits.MAX_VALUE_DEPTH - 1)
        for (raw in listOf(boundary, "1.000000000000000001", "9223372036854775808", "null", "true", "{\"x\":[1,\"雪\"]}")) {
            assertEquals(raw, RuleCodec.decodeJsonValue(raw).toString())
        }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeJsonValue("[$boundary]") }
    }

    @Test fun nodeBoundaryIsInclusiveButExtraNodeIsRejected() {
        val boundary = List(EngineLimits.MAX_JSON_NODES - 1) { "0" }.joinToString(",", "[", "]")
        assertEquals(EngineLimits.MAX_JSON_NODES - 1, RuleCodec.decodeJsonValue(boundary).jsonArray.size)
        val tooMany = List(EngineLimits.MAX_JSON_NODES) { "0" }.joinToString(",", "[", "]")
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeJsonValue(tooMany) }
    }

    @Test fun bytePreflightCountsUtf8NotJustCharacters() {
        val boundary = JsonPrimitive("x".repeat(EngineLimits.MAX_BYTES - 2)).toString()
        assertEquals(EngineLimits.MAX_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(EngineLimits.MAX_BYTES - 2, RuleCodec.decodeJsonValue(boundary).jsonPrimitive.content.length)
        val oversized = JsonPrimitive("雪".repeat(EngineLimits.MAX_BYTES / 3 + 1)).toString()
        assertTrue(oversized.length < EngineLimits.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeJsonValue(oversized) }
    }

    @Test fun duplicateKeysAndMalformedJsonNeverSilentlyOverwriteValues() {
        for (raw in listOf("{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "{\"outer\":{\"x\":1,\"x\":2}}", "[1,]", "NaN", "{} {}")) {
            assertThrows(IllegalArgumentException::class.java) { RuleCodec.decodeJsonValue(raw) }
        }
        assertEquals(JsonPrimitive(1), RuleCodec.decodeJsonValue("{\"x\":1}").jsonObject["x"])
    }
}
