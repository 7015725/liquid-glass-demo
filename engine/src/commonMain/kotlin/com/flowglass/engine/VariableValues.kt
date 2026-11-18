package com.flowglass.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * Converts the six user-visible variable types. This is the single place that implements the
 * integer, boolean and list rules shared by the editor, SET_VAR and the script bridge.
 */
object VariableValues {
    private val trueWords = setOf("true", "真", "1", "是")
    private val falseWords = setOf("false", "假", "0", "否")

    fun parse(raw: String, type: VariableType): Any = when (type) {
        VariableType.INT -> parseInt(raw)
        VariableType.BOOLEAN -> parseBoolean(raw)
        VariableType.TEXT -> raw
        VariableType.INT_LIST -> split(raw).map(::parseInt)
        VariableType.BOOLEAN_LIST -> split(raw).map(::parseBoolean)
        VariableType.TEXT_LIST -> split(raw)
    }

    fun fromJson(element: JsonElement, type: VariableType): Any = when (type) {
        VariableType.INT -> parseInt(element.primitiveText())
        VariableType.BOOLEAN -> parseBoolean(element.primitiveText())
        VariableType.TEXT -> element.primitiveText()
        VariableType.INT_LIST -> element.listTexts().map(::parseInt)
        VariableType.BOOLEAN_LIST -> element.listTexts().map(::parseBoolean)
        VariableType.TEXT_LIST -> element.listTexts()
    }

    /** Script write: an existing variable keeps its declared type. */
    fun fromScript(value: Any?, type: VariableType): Any = when (type) {
        VariableType.INT -> parseInt(value)
        VariableType.BOOLEAN -> parseBoolean(value)
        VariableType.TEXT -> text(value)
        VariableType.INT_LIST -> scriptList(value).map(::parseInt)
        VariableType.BOOLEAN_LIST -> scriptList(value).map(::parseBoolean)
        VariableType.TEXT_LIST -> scriptList(value).map(::text)
    }

    fun inferType(value: Any?): VariableType = when (value) {
        is Boolean -> VariableType.BOOLEAN
        is Number -> if (isWhole(value)) VariableType.INT else VariableType.TEXT
        is List<*> -> when {
            value.all { it is Boolean } -> VariableType.BOOLEAN_LIST
            value.all { it is Number && isWhole(it) } -> VariableType.INT_LIST
            else -> VariableType.TEXT_LIST
        }
        is Array<*> -> inferType(value.toList())
        is JsonArray -> when {
            value.all { (it as? JsonPrimitive)?.contentOrNull in trueWords + falseWords } -> VariableType.BOOLEAN_LIST
            value.all { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() != null } -> VariableType.INT_LIST
            else -> VariableType.TEXT_LIST
        }
        else -> VariableType.TEXT
    }

    /** Text inserted into a notification, shell command, URL or another parameter. */
    fun text(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> if (value) "true" else "false"
        is List<*> -> value.joinToString(",") { text(it) }
        is Array<*> -> value.joinToString(",") { text(it) }
        is JsonArray -> value.joinToString(",") { text(it) }
        is JsonPrimitive -> if (value.isString) value.content else value.content
        else -> value.toString()
    }

    /** Human-facing value in the variable manager and pickers. */
    fun uiText(value: Any?): String = when (value) {
        is Boolean -> if (value) "真" else "假"
        is List<*> -> value.joinToString(",") { uiText(it) }
        is Array<*> -> value.joinToString(",") { uiText(it) }
        is JsonArray -> value.joinToString(",") { uiText(it) }
        else -> text(value)
    }

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonPrimitive("")
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(if (value is Long) value else value.toLong())
        is List<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
        is Array<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
        is JsonArray -> value
        is JsonElement -> value
        else -> JsonPrimitive(value.toString())
    }

    fun parseInt(value: Any?): Long {
        if (value is Long) return value
        if (value is Int || value is Short || value is Byte) return (value as Number).toLong()
        val number = value as? Number
        if (number != null && isWhole(number)) {
            val double = number.toDouble()
            require(double >= -9.007199254740992E15 && double <= 9.007199254740992E15) {
                "『$number』不是整数"
            }
            return double.toLong()
        }
        val raw = value?.toString()?.trim().orEmpty()
        return raw.toLongOrNull() ?: throw IllegalArgumentException("『$raw』不是整数")
    }

    fun parseBoolean(value: Any?): Boolean {
        if (value is Boolean) return value
        if (value is Number) {
            if (value.toDouble() == 1.0) return true
            if (value.toDouble() == 0.0) return false
        }
        val raw = value?.toString()?.trim().orEmpty()
        return when (raw.lowercase()) {
            in trueWords -> true
            in falseWords -> false
            else -> throw IllegalArgumentException("『$raw』不是真假值")
        }
    }

    private fun split(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            }.getOrNull()
            if (parsed is JsonArray) return parsed.listTexts()
        }
        val body = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else trimmed
        return body.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun JsonElement.listTexts(): List<String> = when (this) {
        is JsonArray -> map { it.primitiveText() }
        else -> split(primitiveText())
    }

    private fun JsonElement.primitiveText(): String = when (this) {
        is JsonPrimitive -> content
        is JsonArray -> listTexts().joinToString(",")
        else -> toString()
    }

    private fun scriptList(value: Any?): List<Any?> = when (value) {
        null -> emptyList()
        is List<*> -> value
        is Array<*> -> value.toList()
        is JsonArray -> value.map { if (it is JsonPrimitive) it.content else it.toString() }
        is String -> split(value)
        else -> listOf(value)
    }

    private fun isWhole(number: Number): Boolean {
        val double = number.toDouble()
        return double.isFinite() && double == kotlin.math.floor(double)
    }
}
