package com.flowglass.demo.ui

import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ParameterCodec

/**
 * Turns a component's stored display text into one editable field per [com.flowglass.engine.ParamSpec],
 * and back. The fields are display text for the user; [compose] re-parses them into canonical values
 * so an invalid draft is rejected instead of being written to disk.
 */
internal object ParameterForm {
    fun fields(meta: ComponentMeta, parameter: String): Map<String, String> {
        val parsed = runCatching { ParameterCodec.parseComponent(meta, parameter) }.getOrNull()
        return meta.params.associate { spec ->
            val formatted = parsed?.get(spec.key)?.let { value ->
                runCatching { ParameterCodec.format(spec, value) }.getOrNull()
            }
            val fallback = runCatching { ParameterCodec.format(spec, spec.defaultValue) }.getOrNull()
            spec.key to (formatted ?: when {
                parameter.isBlank() -> fallback.orEmpty()
                // A multi-field component stores "|" separators. Showing the whole raw string in
                // the first field would leak those separators into a fresh draft (e.g. "||||").
                meta.params.size == 1 && spec == meta.params.firstOrNull() -> parameter
                else -> ""
            })
        }
    }

    fun compose(meta: ComponentMeta, fields: Map<String, String>): Result<String> = runCatching {
        val params = meta.params.associate { spec ->
            spec.key to ParameterCodec.parse(spec, fields[spec.key].orEmpty())
        }
        val text = ParameterCodec.formatComponent(meta, params)
        // A field that contains the component's own separator (e.g. "|" in 通知) would be
        // re-split on the next read; reject it instead of silently moving text between fields.
        require(ParameterCodec.parseComponent(meta, text) == params) {
            "参数包含分隔符或无法无歧义保存的字符"
        }
        text
    }

    fun fieldLabel(spec: com.flowglass.engine.ParamSpec): String =
        if (spec.suffix.isEmpty()) spec.label else "${spec.label}（${spec.suffix}）"

    /**
     * UI-level checks for trigger values that are syntactically valid but can never match.
     * These are deliberately not in ParameterCodec: old rules must still load so the user can
     * repair them, even if an old token or location is incomplete.
     */
    fun triggerProblem(meta: ComponentMeta, fields: Map<String, String>): String? {
        if (meta.kind != ComponentKind.TRIGGER) return null
        fun value(key: String) = fields[key].orEmpty().trim()
        val tokenSpec = meta.params.firstOrNull { it.key == "token" }
        if (tokenSpec != null) {
            val token = value("token")
            // Empty token is the explicit open mode. Only validate a token the user opted into.
            if (token.isNotEmpty()) {
                if (token.length < 16) return "令牌至少需要 16 个字符，或者留空开放入口。"
                if (token.any { it.isWhitespace() }) return "令牌不能包含空白字符。"
            }
        }
        if (meta.typeId.startsWith("trigger.location_")) {
            val raw = value("location")
            val parts = raw.split(',')
            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (parts.size != 2 || lat == null || lng == null ||
                lat !in -90.0..90.0 || lng !in -180.0..180.0
            ) return "地点必须填写为“纬度,经度”，例如 39.9042,116.4074。"
        }
        if (value("matchMode") in setOf("regex", "正则表达式")) {
            val keys = when (meta.typeId) {
                "trigger.notification", "trigger.notification_removed" ->
                    listOf("title", "text", "subtext")
                "trigger.sms_received" -> listOf("keyword")
                "trigger.wifi" -> listOf("ssid")
                "trigger.bluetooth" -> listOf("name")
                else -> emptyList()
            }
            for (key in keys) {
                val pattern = value(key)
                if (pattern.isEmpty()) continue
                if (pattern.length > 512) return "正则表达式不能超过 512 个字符。"
                if (runCatching { Regex(pattern) }.isFailure) return "正则表达式无效，请检查括号和转义。"
            }
        }
        for (spec in meta.params) {
            if (!spec.required || spec.type !in setOf(
                    com.flowglass.engine.ParamType.TEXT,
                    com.flowglass.engine.ParamType.APPLICATION,
                )
            ) continue
            val current = value(spec.key)
            if (current.isEmpty()) return "${spec.label}不能为空。"
            if (spec.type == com.flowglass.engine.ParamType.APPLICATION &&
                current == "应用包名"
            ) return "请选择${spec.label}。"
        }
        return null
    }
}
