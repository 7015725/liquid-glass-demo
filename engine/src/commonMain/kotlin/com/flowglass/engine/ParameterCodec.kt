package com.flowglass.engine

import kotlinx.serialization.json.*

/** The only adapter between v0.5 editor text and canonical v2 parameter values. */
object ParameterCodec {
    val switchOptions = listOf(ParamOption("on", "开启"), ParamOption("off", "关闭"), ParamOption("toggle", "切换"))
    private val clock = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    private val weekdayPattern = Regex("^[1-7](,[1-7])*$")
    private val weekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** Accepts 每天 / 工作日 / 周末 or a comma/、/space separated 1–7 list; stores canonical `1,2,3`. */
    fun weekdays(text: String): String {
        val value = text.trim()
        val days = when (value.lowercase()) {
            "每天", "everyday", "all" -> (1..7).toList()
            "工作日", "workday", "weekdays" -> (1..5).toList()
            "周末", "weekend" -> listOf(6, 7)
            else -> value.split(',', '，', '、', ' ', '/')
                .filter { it.isNotBlank() }
                .map { part ->
                    val numeric = part.trim().toIntOrNull()
                    val label = weekdayLabels.indexOf(part.trim()).takeIf { it >= 0 }?.plus(1)
                    numeric ?: label ?: throw IllegalArgumentException("星期必须为 周一–周日 或 1–7")
                }
                .also { require(it.isNotEmpty()) { "请至少选择一个星期" } }
                .distinct().sorted()
        }
        require(days.isNotEmpty() && days.all { it in 1..7 }) { "星期必须在 1–7 之间" }
        return days.joinToString(",")
    }

    fun weekdayLabel(canonical: String): String {
        val days = canonical.split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }
        return when (days) {
            (1..7).toList() -> "每天"
            (1..5).toList() -> "工作日"
            listOf(6, 7) -> "周末"
            else -> days.joinToString("、") { weekdayLabels[it - 1] }
        }
    }

    fun parse(spec: ParamSpec, text: String): JsonElement {
        val value = text.trim()
        val result: JsonElement = when (spec.type) {
            ParamType.TEXT, ParamType.MULTILINE_TEXT, ParamType.APPLICATION -> JsonPrimitive(text)
            ParamType.INTEGER -> JsonPrimitive(integer(value))
            ParamType.PERCENTAGE -> {
                val number = decimalScaled(value.removeSuffix("%").removeSuffix("％").trim(), 1)
                require(number in 0..100) { "百分比必须是 0–100 的整数" }
                JsonPrimitive(number)
            }
            ParamType.DURATION -> {
                require(value.length <= 128) { "时长过长" }
                val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(毫秒|秒|分钟)").matchEntire(value)
                    ?: throw IllegalArgumentException("时长期望数字 + 毫秒/秒/分钟")
                val factor = when (match.groupValues[2]) { "秒" -> 1000L; "分钟" -> 60000L; else -> 1L }
                JsonPrimitive(decimalScaled(match.groupValues[1], factor))
            }
            ParamType.TIME -> { require(clock.matches(value)) { "时刻必须为 HH:mm" }; JsonPrimitive(value) }
            ParamType.TIME_RANGE -> {
                val parts = value.split(Regex("\\s*[–—~-]\\s*"))
                require(parts.size == 2 && parts.all(clock::matches)) { "时间范围必须为 HH:mm–HH:mm" }
                buildJsonObject { put("from", parts[0]); put("to", parts[1]) }
            }
            ParamType.INT_RANGE -> {
                val parts = value.split(Regex("\\s*[–—~]\\s*|\\s+-\\s+"))
                require(parts.size == 2) { "整数范围必须包含两个端点" }
                fun endpoint(part: String): Long = integer(if (spec.suffix.isEmpty()) part else
                    part.removeSuffix(spec.suffix).removeSuffix(if (spec.suffix == "%") "％" else spec.suffix).trim())
                buildJsonObject { put("min", endpoint(parts[0])); put("max", endpoint(parts[1])) }
            }
            ParamType.TRI_STATE -> {
                val token = when (value.lowercase()) {
                    "开启", "开", "on", "true", "1" -> "on"
                    "关闭", "关", "off", "false", "0" -> "off"
                    "切换", "toggle" -> "toggle"
                    else -> throw IllegalArgumentException("三态必须为开启/关闭/切换")
                }
                JsonPrimitive(token)
            }
            ParamType.WEEKDAYS -> JsonPrimitive(weekdays(value))
            ParamType.ENUM -> JsonPrimitive(spec.options.firstOrNull { it.value == value || it.label == value }?.value
                ?: throw IllegalArgumentException("枚举选项无效"))
        }
        validate(spec, result)
        return result
    }

    fun validate(spec: ParamSpec, value: JsonElement) {
        fun string(): String {
            require(value is JsonPrimitive && value.isString) { "${spec.key} 必须为字符串" }
            return value.content
        }
        fun number(element: JsonElement = value): Long {
            require(element is JsonPrimitive && !element.isString && Regex("-?(?:0|[1-9][0-9]*)").matches(element.content)) {
                "${spec.key} 必须为 JSON 整数"
            }
            return requireNotNull(element.longOrNull) { "整数溢出" }
        }
        fun bounds(n: Long) {
            require(spec.minValue == null || n >= spec.minValue) { "小于下限" }
            require(spec.maxValue == null || n <= spec.maxValue) { "大于上限" }
        }
        when (spec.type) {
            ParamType.TEXT, ParamType.MULTILINE_TEXT, ParamType.APPLICATION -> string()
            ParamType.INTEGER -> bounds(number())
            ParamType.PERCENTAGE -> require(number() in 0..100) { "百分比必须是 0–100 的整数" }
            ParamType.DURATION -> require(number() >= 0) { "时长不能为负" }
            ParamType.TIME -> require(clock.matches(string())) { "时刻必须为 HH:mm" }
            ParamType.TRI_STATE -> require(string() in setOf("on", "off", "toggle")) { "三态值无效" }
            ParamType.WEEKDAYS -> {
                val text = string()
                require(weekdayPattern.matches(text)) { "星期必须为 1–7 的逗号列表" }
                val days = text.split(',').map { requireNotNull(it.toIntOrNull()) }
                require(days == days.distinct().sorted()) { "星期必须去重并升序" }
            }
            ParamType.ENUM -> require(spec.options.any { it.value == string() }) { "枚举值无效" }
            ParamType.TIME_RANGE -> {
                require(value is JsonObject && value.keys == setOf("from", "to")) { "时间范围结构无效" }
                for (key in listOf("from", "to")) {
                    val endpoint = value[key]
                    require(endpoint is JsonPrimitive && endpoint.isString && clock.matches(endpoint.content)) { "时间范围端点无效" }
                }
            }
            ParamType.INT_RANGE -> {
                require(value is JsonObject && value.keys == setOf("min", "max")) { "整数范围结构无效" }
                val min = number(value.getValue("min")); val max = number(value.getValue("max"))
                require(min <= max) { "范围下限不能大于上限" }
                bounds(min); bounds(max)
            }
        }
    }

    fun format(spec: ParamSpec, value: JsonElement?): String {
        if (value == null) return ""
        validate(spec, value)
        return when (spec.type) {
            ParamType.PERCENTAGE -> "${value.jsonPrimitive.content}%"
            ParamType.DURATION -> {
                val n = value.jsonPrimitive.long
                when {
                    n == 0L -> "0 秒"
                    n % 60000L == 0L -> "${n / 60000L} 分钟"
                    n % 1000L == 0L -> "${n / 1000L} 秒"
                    else -> "$n 毫秒"
                }
            }
            ParamType.TIME_RANGE -> "${value.jsonObject.getValue("from").jsonPrimitive.content}–${value.jsonObject.getValue("to").jsonPrimitive.content}"
            ParamType.INT_RANGE -> "${value.jsonObject.getValue("min").jsonPrimitive.content}${spec.suffix}–${value.jsonObject.getValue("max").jsonPrimitive.content}${spec.suffix}"
            ParamType.TRI_STATE -> (spec.options.ifEmpty { switchOptions }).first { it.value == value.jsonPrimitive.content }.label
            ParamType.WEEKDAYS -> weekdayLabel(value.jsonPrimitive.content)
            ParamType.ENUM -> spec.options.first { it.value == value.jsonPrimitive.content }.label
            else -> value.jsonPrimitive.content
        }
    }

    fun parseComponent(meta: ComponentMeta, text: String): Map<String, JsonElement> {
        if (meta.typeId == "action.post_notification") {
            val split = text.indexOf('|')
            return mapOf("title" to JsonPrimitive(if (split < 0) "" else text.substring(0, split)),
                "content" to JsonPrimitive(if (split < 0) text else text.substring(split + 1)))
        }
        if (meta.params.isEmpty()) { require(text.isEmpty()) { "无参数组件包含未识别文字" }; return emptyMap() }
        if (meta.params.size > 1) {
            val parts = text.split('|')
            require(parts.size == meta.params.size) { "参数需用 | 分隔为 ${meta.params.size} 项" }
            return meta.params.mapIndexed { index, spec -> spec.key to parse(spec, parts[index]) }.toMap()
        }
        val spec = meta.params.single()
        return mapOf(spec.key to parse(spec, text))
    }

    fun formatComponent(meta: ComponentMeta, params: Map<String, JsonElement>): String {
        if (meta.typeId == "action.post_notification") {
            fun field(key: String) = format(meta.params.single { it.key == key }, params[key])
            val title = field("title")
            require('|' !in title) { "通知标题不能包含 |" }
            return "$title|${field("content")}"
        }
        if (meta.params.size > 1) {
            val values = meta.params.map { format(it, params[it.key]) }
            require(values.none { '|' in it }) { "参数不能包含 |" }
            return values.joinToString("|")
        }
        return meta.params.singleOrNull()?.let { format(it, params[it.key]) }.orEmpty()
    }

    /** Checks declared values, not completeness: incomplete editor drafts can omit keys. */
    fun validateDocument(document: RuleDocument) {
        val metadata = BuiltInComponents.all.associateBy { it.typeId }
        for (rule in document.rules) for (component in rule.triggers + rule.conditions + rule.actions) {
            if (component.type == "action.post_notification") {
                val title = component.params["title"]
                require(title == null || title is JsonPrimitive && title.isString && '|' !in title.content) {
                    "通知标题不能包含 |"
                }
            }
            metadata[component.type]?.params?.forEach { spec ->
                component.params[spec.key]?.let { validate(spec, it) }
            }
        }
    }

    private fun integer(text: String): Long {
        require(text.length <= 20 && Regex("[+-]?[0-9]+").matches(text)) { "期望整数" }
        return requireNotNull(text.toLongOrNull()) { "整数溢出" }
    }

    // Decimal string arithmetic avoids Double rounding and silent sub-millisecond loss.
    private fun decimalScaled(text: String, factor: Long): Long {
        require(text.length <= 128 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(text)) { "期望非负数字" }
        val whole = requireNotNull(text.substringBefore('.').toLongOrNull()) { "整数溢出" }
        require(whole <= Long.MAX_VALUE / factor) { "整数溢出" }
        val fraction = text.substringAfter('.', "").trimEnd('0')
        var remainder = 0L
        if (fraction.isNotEmpty()) {
            require(fraction.length <= 18) { "不能精确表示为整数" }
            var scale = 1L
            repeat(fraction.length) { scale *= 10L }
            val digits = fraction.toLong()
            // Reduce factor/scale before multiplication, so valid small fractions do not overflow.
            fun gcd(a: Long, b: Long): Long { var x = a; var y = b; while (y != 0L) { val r = x % y; x = y; y = r }; return x }
            val divisor = gcd(factor, scale)
            require(digits % (scale / divisor) == 0L) { "不能精确表示为整数" }
            remainder = (digits / (scale / divisor)) * (factor / divisor)
        }
        val integral = whole * factor
        require(integral <= Long.MAX_VALUE - remainder) { "整数溢出" }
        return integral + remainder
    }
}
