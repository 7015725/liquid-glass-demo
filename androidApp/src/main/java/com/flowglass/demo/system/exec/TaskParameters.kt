package com.flowglass.demo.system.exec

internal enum class SwitchMode { ON, OFF, TOGGLE }

internal data class HttpRequestSpec(val method: String, val url: java.net.URL, val body: String?)

internal object TaskParameters {
    fun http(text: String): HttpRequestSpec {
        val expected = "GET url / POST url 内容"
        val value = text.trimStart()
        if (value.length > 1_048_576) invalid(expected)
        val separator = value.indexOfFirst { it.isWhitespace() }
        if (separator < 1) invalid(expected)
        val method = value.substring(0, separator).uppercase(java.util.Locale.ROOT)
        if (method != "GET" && method != "POST") invalid(expected)
        val rest = value.substring(separator).trimStart()
        val end = rest.indexOfFirst { it.isWhitespace() }.let { if (it < 0) rest.length else it }
        val url = try { java.net.URL(rest.substring(0, end)).also { it.toURI() } }
            catch (_: Exception) { invalid(expected) }
        if (url.protocol !in listOf("http", "https") || url.host.isBlank()) invalid(expected)
        val body = if (end < rest.length) rest.substring(end + 1) else ""
        if (method == "GET" && body.isNotBlank()) invalid(expected)
        return HttpRequestSpec(method, url, if (method == "POST") body else null)
    }
    fun mode(text: String): SwitchMode = when (text.trim().lowercase(java.util.Locale.ROOT)) {
        "开启", "开", "on", "true", "1" -> SwitchMode.ON
        "关闭", "关", "off", "false", "0" -> SwitchMode.OFF
        "切换", "toggle" -> SwitchMode.TOGGLE
        else -> invalid("开启 / 关闭 / 切换")
    }
    private fun invalid(expected: String): Nothing =
        throw IllegalArgumentException("参数格式不正确：期望 $expected")

    fun delayMillis(text: String): Long {
        val expected = "1 秒 / 500 毫秒 / 2 分钟（最多 10 分钟）"
        val value = text.trim()
        if (value.length > 128) invalid(expected)
        val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(毫秒|秒|分钟)""").matchEntire(value) ?: invalid(expected)
        val factor = when (match.groupValues[2]) { "秒" -> 1000L; "分钟" -> 60000L; else -> 1L }
        return match.groupValues[1].toBigDecimal()
            .multiply(java.math.BigDecimal.valueOf(factor))
            .min(java.math.BigDecimal.valueOf(600000L)).toLong()
    }

    fun percent(text: String): Double {
        val expected = "0–100% 的百分比"
        val value = text.trim().removeSuffix("%").removeSuffix("％").trim()
        if (value.length > 128 || !Regex("""[0-9]+(?:\.[0-9]+)?""").matches(value)) invalid(expected)
        val number = value.toBigDecimal()
        if (number < java.math.BigDecimal.ZERO || number > java.math.BigDecimal.valueOf(100)) invalid(expected)
        return number.toDouble()
    }
}
