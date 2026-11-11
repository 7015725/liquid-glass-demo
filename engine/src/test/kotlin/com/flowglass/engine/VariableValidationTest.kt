package com.flowglass.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VariableValidationTest {
    private fun v(type: String, initial: String) = Variable("value", type, Json.parseToJsonElement(initial))
    @Test fun integerIsStrictSignedLongWithoutDoubleRounding() {
        listOf("9223372036854775807", "-9223372036854775808", "9007199254740993", "0").forEach {
            assertTrue(it, RuleValidator.validateVariable(v("int", it)).isEmpty())
        }
        listOf("9223372036854775808", "-9223372036854775809", "1.0", "1e0", "\"42\"", "true", "null").forEach {
            assertTrue(it, RuleValidator.validateVariable(v("int", it)).any { issue -> issue.path == "initial" })
        }
    }
    @Test fun everyVariableTypeChecksJsonShape() {
        val good = mapOf("string" to "\"hi\"", "decimal" to "3.14", "bool" to "true", "list" to "[1,\"a\",[2,3]]", "dict" to "{\"name\":\"x\",\"count\":2}", "null" to "null")
        good.forEach { (type, text) -> assertTrue(type, RuleValidator.validateVariable(v(type, text)).isEmpty()) }
        val bad = mapOf("string" to "1", "decimal" to "1e999", "bool" to "\"true\"", "list" to "{}", "dict" to "[]", "null" to "\"null\"", "unknown" to "null")
        bad.forEach { (type, text) -> assertTrue(type, RuleValidator.validateVariable(v(type, text)).isNotEmpty()) }
    }
    @Test fun variableNamesAreUnqualifiedWritableIdentifiers() {
        listOf("", "  ", "local.count", "global.mode", "event.title", "a-b", "1abc").forEach {
            assertTrue(it, RuleValidator.validateVariable(Variable(it)).any { issue -> issue.path == "name" })
        }
        assertTrue(RuleValidator.validateVariable(Variable("计数_2")).isEmpty())
    }
}
