package com.flowglass.engine

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** Preflight before the recursive serializer: bound input and reject key overwrite. */
internal object StrictJsonInput {
    fun parse(text: String): JsonElement {
        require(text.length <= EngineLimits.MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= EngineLimits.MAX_BYTES) { "JSON 文件超过1 MiB" }
        Scanner(text).check()
        return RuleCodec.json.parseToJsonElement(text)
    }
    private class Scanner(val text: String) {
        var pos = 0
        var nodes = 0
        fun fail(message: String): Nothing = throw IllegalArgumentException("JSON[$pos]：$message")
        fun whitespace() { while (pos < text.length && text[pos] in " \t\r\n") pos++ }
        fun expect(c: Char) { whitespace(); if (pos >= text.length || text[pos++] != c) fail("期待 $c") }
        fun peek(c: Char): Boolean { whitespace(); return pos < text.length && text[pos] == c }
        fun string(): String {
            whitespace()
            val start = pos
            expect('"')
            while (pos < text.length) {
                val c = text[pos++]
                if (c == '"') return RuleCodec.json.decodeFromString<String>(text.substring(start, pos))
                if (c < ' ') fail("字符串包含未转义控制字符")
                if (c == '\\') {
                    if (pos >= text.length) fail("转义未结束")
                    val escaped = text[pos++]
                    if (escaped == 'u') {
                        repeat(4) { if (pos >= text.length || text[pos++].digitToIntOrNull(16) == null) fail("非法 Unicode 转义") }
                    } else if (escaped !in "\"\\/bfnrt") fail("非法转义")
                }
            }
            fail("字符串未结束")
        }
        fun value(depth: Int) {
            whitespace()
            if (++nodes > EngineLimits.MAX_JSON_NODES) fail("JSON 节点过多")
            if (depth > EngineLimits.MAX_JSON_DEPTH) fail("JSON 嵌套超过 ${EngineLimits.MAX_JSON_DEPTH} 层")
            if (pos >= text.length) fail("缺少值")
            when (text[pos]) {
                '{' -> {
                    pos++
                    val keys = mutableSetOf<String>()
                    if (!peek('}')) while (true) {
                        val key = string()
                        if (!keys.add(key)) fail("重复 JSON 键：$key")
                        expect(':'); value(depth + 1)
                        if (!peek(',')) break
                        pos++
                    }
                    expect('}')
                }
                '[' -> {
                    pos++
                    if (!peek(']')) while (true) {
                        value(depth + 1)
                        if (!peek(',')) break
                        pos++
                    }
                    expect(']')
                }
                '"' -> string()
                else -> {
                    val start = pos
                    while (pos < text.length && text[pos] !in " \t\r\n,]}") pos++
                    val token = text.substring(start, pos)
                    if (token !in setOf("true", "false", "null") && !NUMBER.matches(token)) fail("非法 JSON 值")
                }
            }
        }
        fun check() { value(1); whitespace(); if (pos != text.length) fail("存在多余内容") }
    }
    private val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

    fun shape(element: JsonElement, bundle: Boolean) {
        if (bundle) {
            val obj = element as? JsonObject ?: throw IllegalArgumentException("根节点必须是对象")
            integer(obj, "formatVersion", "formatVersion", int = true)
            array(obj, "rules", "rules")?.forEachIndexed { i, value -> rule(value, "rules[$i]") }
            array(obj, "globals", "globals")?.forEachIndexed { i, value -> variable(value, "globals[$i]") }
        } else rule(element, "rule")
    }
    private fun obj(value: JsonElement, p: String): JsonObject = value as? JsonObject ?: throw IllegalArgumentException("$p 必须是对象")
    private fun strings(o: JsonObject, p: String, vararg keys: String) { keys.forEach { k -> o[k]?.let { require(it is JsonPrimitive && it.isString) { "$p.$k 必须是字符串" } } } }
    private fun bools(o: JsonObject, p: String, vararg keys: String) { keys.forEach { k -> o[k]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "$p.$k 必须是布尔值" } } } }
    private fun integer(o: JsonObject, k: String, p: String, int: Boolean = false, nullable: Boolean = false) {
        val v = o[k] ?: return
        if (nullable && v === JsonNull) return
        val n = RuleValidator.strictLong(v)
        require(n != null && (!int || n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())) { "$p 必须是${if (int) 32 else 64}位整数，不接受字符串或溢出" }
    }
    private fun array(o: JsonObject, k: String, p: String): JsonArray? = o[k]?.let { it as? JsonArray ?: throw IllegalArgumentException("$p 必须是数组") }
    private fun rule(value: JsonElement, p: String) {
        val o = obj(value, p)
        strings(o, p, "id", "name", "description", "type", "runMode")
        bools(o, p, "enabled", "pinned")
        integer(o, "schemaVersion", "$p.schemaVersion", int = true)
        integer(o, "debounceMillis", "$p.debounceMillis")
        listOf("triggers", "conditions", "actions").forEach { key -> array(o, key, "$p.$key")?.forEachIndexed { i, v -> component(v, "$p.$key[$i]", 1) } }
        array(o, "locals", "$p.locals")?.forEachIndexed { i, v -> variable(v, "$p.locals[$i]") }
        listOf("triggerMatch", "conditionMatch").forEach { key -> o[key]?.let { match(it, "$p.$key") } }
    }
    private fun component(value: JsonElement, p: String, depth: Int) {
        require(depth <= EngineLimits.MAX_DEPTH) { "$p 组件嵌套超过 ${EngineLimits.MAX_DEPTH} 层" }
        val o = obj(value, p)
        strings(o, p, "id", "type", "onError")
        integer(o, "timeoutSeconds", "$p.timeoutSeconds", int = true)
        integer(o, "retryCount", "$p.retryCount", int = true)
        o["params"]?.let { obj(it, "$p.params") }
        listOf("children", "elseActions").forEach { key -> array(o, key, "$p.$key")?.forEachIndexed { i, v -> component(v, "$p.$key[$i]", depth + 1) } }
    }
    private fun variable(value: JsonElement, p: String) {
        val o = obj(value, p); strings(o, p, "name", "type"); bools(o, p, "resetEachRun")
    }
    private fun match(value: JsonElement, p: String) {
        val o = obj(value, p); strings(o, p, "mode")
        integer(o, "windowSeconds", "$p.windowSeconds", int = true)
        integer(o, "atLeast", "$p.atLeast", int = true, nullable = true)
        o["tree"]?.takeUnless { it === JsonNull }?.let { node(it, "$p.tree", 1) }
    }
    private fun node(value: JsonElement, p: String, depth: Int) {
        require(depth <= EngineLimits.MAX_DEPTH) { "$p 匹配树嵌套超过 ${EngineLimits.MAX_DEPTH} 层" }
        val o = obj(value, p); strings(o, p, "op")
        if (o["ref"] !== JsonNull) strings(o, p, "ref")
        array(o, "children", "$p.children")?.forEachIndexed { i, v -> node(v, "$p.children[$i]", depth + 1) }
    }
}
