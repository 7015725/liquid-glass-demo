package com.flowglass.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

class ParameterCodecTest {
    private fun verify(spec: ParamSpec, input: String, value: JsonElement, display: String,
                       badText: String? = null, badValue: JsonElement = JsonNull) {
        assertEquals(value, ParameterCodec.parse(spec, input))
        assertEquals(display, ParameterCodec.format(spec, value))
        val meta = ComponentMeta("test.parameter", "测试", "测试", ComponentKind.ACTION, listOf(spec))
        assertEquals(mapOf(spec.key to value), ParameterCodec.parseComponent(meta, input))
        val document = RuleDocument(rules = listOf(Rule("id", actions = listOf(RuleComponent(meta.typeId, mapOf(spec.key to value))))))
        assertEquals(document, RuleJson.decode(RuleJson.encode(document)))
        assertEquals(spec, RuleJson.format.decodeFromString<ParamSpec>(RuleJson.format.encodeToString(spec)))
        assertFailsWith<IllegalArgumentException> { ParameterCodec.validate(spec, badValue) }
        if (badText != null) assertFailsWith<IllegalArgumentException> { ParameterCodec.parse(spec, badText) }
    }
    @Test fun textRoundTripPreservesWhitespaceAndRejectsNonStringJson() = verify(
        ParamSpec("text", "文本"), "  原文 | 保留  ", JsonPrimitive("  原文 | 保留  "), "  原文 | 保留  ", badValue = JsonPrimitive(4))
    @Test fun multilineRoundTripPreservesNewlinesAndRejectsArrayJson() = verify(
        ParamSpec("text", "多行", ParamType.MULTILINE_TEXT), "\t第一行\n第二行", JsonPrimitive("\t第一行\n第二行"), "\t第一行\n第二行", badValue = JsonArray(emptyList()))
    @Test fun applicationRoundTripPreservesPackageAndRejectsObjectJson() = verify(
        ParamSpec("package", "应用", ParamType.APPLICATION), "com.example.app", JsonPrimitive("com.example.app"), "com.example.app", badValue = JsonObject(emptyMap()))
    @Test fun integerRoundTripRejectsFractionAndOverflow() {
        verify(ParamSpec("n", "整数", ParamType.INTEGER), "-42", JsonPrimitive(-42), "-42", "1.2", JsonPrimitive("42"))
        assertFailsWith<IllegalArgumentException> { ParameterCodec.parse(ParamSpec("n", "整数", ParamType.INTEGER), "9223372036854775808") }
    }
    @Test fun percentageRoundTripIsNumericAndBounded() {
        val spec = ParamSpec("n", "百分比", ParamType.PERCENTAGE)
        verify(spec, "50%", JsonPrimitive(50), "50%", "101%", JsonPrimitive("50%"))
        for (text in listOf("-1%", "50.5%", "NaN", "", " ")) assertFailsWith<IllegalArgumentException> { ParameterCodec.parse(spec, text) }
        assertEquals(JsonPrimitive(50), ParameterCodec.parse(spec, "50.0％"))
        for (n in listOf(0, 100)) assertEquals(JsonPrimitive(n), ParameterCodec.parse(spec, "$n%"))
    }
    @Test fun durationRoundTripUsesExactIntegerMilliseconds() {
        val spec = ParamSpec("duration", "时长", ParamType.DURATION)
        verify(spec, "1.5 秒", JsonPrimitive(1500), "1500 毫秒", "-1 秒", JsonPrimitive("1000"))
        assertEquals(JsonPrimitive(120000), ParameterCodec.parse(spec, "2 分钟"))
        assertEquals(JsonPrimitive(1200000), ParameterCodec.parse(spec, "20 分钟"))
        assertEquals(JsonPrimitive(1), ParameterCodec.parse(spec, "0.001 秒"))
        for (text in listOf("0.0001 秒", "0.5 毫秒", "9223372036854775808 毫秒", "9223372036854775807 秒", "1 小时", "1", "NaN 秒")) {
            assertFailsWith<IllegalArgumentException> { ParameterCodec.parse(spec, text) }
        }
    }
    @Test fun triStateRoundTripSeparatesValueAndLabel() {
        val spec = ParamSpec("mode", "三态", ParamType.TRI_STATE, options = ParameterCodec.switchOptions)
        verify(spec, "开启", JsonPrimitive("on"), "开启", "自动", JsonPrimitive("开启"))
        for ((input, output) in mapOf("关闭" to "off", "切换" to "toggle", "ON" to "on", "1" to "on", "false" to "off"))
            assertEquals(JsonPrimitive(output), ParameterCodec.parse(spec, input))
    }
    @Test fun timeRoundTripAcceptsOnlyDayClock() {
        val spec = ParamSpec("time", "时刻", ParamType.TIME)
        verify(spec, "08:05", JsonPrimitive("08:05"), "08:05", "24:00", JsonPrimitive("8:05"))
        for (input in listOf("12:60", "1 秒", "08:00–22:00")) assertFailsWith<IllegalArgumentException> { ParameterCodec.parse(spec, input) }
    }
    @Test fun timeRangeRoundTripPreservesBothEndsAndCrossMidnight() {
        val spec = ParamSpec("range", "时间范围", ParamType.TIME_RANGE)
        verify(spec, "22:00–07:00", obj("""{"from":"22:00","to":"07:00"}"""), "22:00–07:00", "22:00–24:00", obj("""{"from":"22:00"}"""))
    }
    @Test fun integerRangeRoundTripBoundsBothEndpoints() {
        val spec = ParamSpec("range", "电量范围", ParamType.INT_RANGE, minValue = 0, maxValue = 100, suffix = "%")
        verify(spec, "20%–80%", obj("""{"min":20,"max":80}"""), "20%–80%", "80%–20%", obj("""{"min":"20","max":80}"""))
        for (input in listOf("-1%–80%", "20%–101%", "20%", "20.5%–80%")) assertFailsWith<IllegalArgumentException> { ParameterCodec.parse(spec, input) }
    }
    @Test fun enumRoundTripSeparatesValueAndLabel() = verify(
        ParamSpec("network", "网络", ParamType.ENUM, options = listOf(ParamOption("wifi", "WiFi"), ParamOption("mobile", "移动网络"))),
        "移动网络", JsonPrimitive("mobile"), "移动网络", "卫星网络", JsonPrimitive("移动网络"))

    @Test fun weekdaysRoundTripNormalisesLabelsAndRejectsEmptySelection() {
        val spec = ParamSpec("days", "重复", ParamType.WEEKDAYS)
        verify(spec, "工作日", JsonPrimitive("1,2,3,4,5"), "工作日", "周八", JsonPrimitive("1,8"))
        assertEquals(JsonPrimitive("1,3,5"), ParameterCodec.parse(spec, "周一、周三、周五"))
        assertEquals(JsonPrimitive("6,7"), ParameterCodec.parse(spec, "周末"))
        assertEquals(JsonPrimitive("1,2,3,4,5,6,7"), ParameterCodec.parse(spec, "每天"))
        assertEquals("周一、周三", ParameterCodec.format(spec, JsonPrimitive("1,3")))
        for (input in listOf("", "周八", "0")) {
            assertFailsWith<IllegalArgumentException> { ParameterCodec.parse(spec, input) }
        }
    }

    @Test fun everyParamTypeHasExactlyOneDedicatedRoundTripCase() {
        assertEquals(setOf("TEXT", "MULTILINE_TEXT", "INTEGER", "PERCENTAGE", "DURATION", "TRI_STATE", "TIME", "TIME_RANGE", "INT_RANGE", "WEEKDAYS", "APPLICATION", "ENUM"), ParamType.entries.map { it.name }.toSet())
    }
    @Test fun everyRegistryDefaultIsCanonical() {
        assertTrue(BuiltInComponents.all.isNotEmpty())
        for (meta in BuiltInComponents.all) for (spec in meta.params) {
            ParameterCodec.validate(spec, spec.defaultValue)
            if (spec.type in listOf(ParamType.ENUM, ParamType.TRI_STATE)) {
                assertTrue(spec.options.all { Regex("[a-z]+(?:_[a-z]+)*").matches(it.value) })
                assertEquals(spec.options.size, spec.options.map { it.value }.distinct().size)
            }
        }
    }
    @Test fun enumSerialNamesRemainLowercaseAndUnknownTokensAreRejected() {
        for (type in RuleType.entries) for (run in RunMode.entries) for (match in MatchMode.entries) for (error in OnError.entries) {
            val rule = Rule("id", type = type, runMode = run, triggerMatch = MatchSpec(mode = match), actions = listOf(RuleComponent("future", onError = error)))
            val encoded = RuleJson.encode(RuleDocument(rules = listOf(rule)))
            assertEquals(rule, RuleJson.decode(encoded).rules.single())
            val json = obj(encoded).jsonObject["rules"]!!.jsonArray.single().jsonObject
            assertEquals(type.name.lowercase(), json["type"]!!.jsonPrimitive.content)
            assertEquals(run.name.lowercase(), json["runMode"]!!.jsonPrimitive.content)
            assertEquals(match.name.lowercase(), json["triggerMatch"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertEquals(error.name.lowercase(), json["actions"]!!.jsonArray.single().jsonObject["onError"]!!.jsonPrimitive.content)
        }
        val base = RuleJson.encode(RuleDocument(rules = listOf(Rule("id", actions = listOf(RuleComponent("future"))))))
        for ((old, new) in listOf("\"type\":\"auto\"" to "\"type\":\"AUTO\"", "\"runMode\":\"drop\"" to "\"runMode\":\"later\"", "\"mode\":\"any\"" to "\"mode\":\"other\"", "\"onError\":\"stop\"" to "\"onError\":\"ignore\""))
            assertFailsWith<IllegalArgumentException> { RuleJson.decode(base.replace(old, new)) }
    }
    @Test fun validatorRejectsNullRequiredValueAndImpossibleMatchThreshold() {
        val rule = Rule("id", name = "test", triggerMatch = MatchSpec(MatchMode.CUSTOM, atLeast = 1),
            actions = listOf(RuleComponent("action.launch_app", mapOf("package" to JsonNull))))
        val issues = RuleValidator.validate(rule, BuiltInComponents.registry())
        assertTrue(issues.any { it.path == "triggerMatch.atLeast" })
        assertTrue(issues.any { it.path == "actions[0].params.package" })
    }
    private fun obj(text: String) = RuleJson.format.parseToJsonElement(text)
}
