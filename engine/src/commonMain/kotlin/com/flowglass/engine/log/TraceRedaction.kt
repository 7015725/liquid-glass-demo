package com.flowglass.engine.log

import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ParamEditor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

object TraceRedaction {
    private val secretKey = Regex(
        "(?i)(token|password|secret|authorization|cookie|api[-_]?key|clipboard\\.(text|content)|剪贴板内容)",
    )
    private const val MAX_VALUE = 512

    fun text(key: String, raw: String, force: Boolean = false): String {
        if (force || secretKey.containsMatchIn(key)) return "***"
        return if (raw.length <= MAX_VALUE) raw else raw.take(MAX_VALUE) + "…"
    }

    fun params(
        meta: ComponentMeta?,
        params: Map<String, JsonElement>,
    ): Map<String, String> {
        val specs = meta?.params?.associateBy { it.key }.orEmpty()
        return params.entries.associate { (key, value) ->
            val spec = specs[key]
            key to text(
                key,
                if (value is JsonPrimitive) value.content else value.toString(),
                force = spec?.editor == ParamEditor.TOKEN || spec?.sensitive == true,
            )
        }
    }

    fun values(values: Map<String, Any?>): Map<String, String> = values.entries.associate { (key, value) ->
        key to text(
            key,
            when (value) {
                null -> ""
                is JsonElement -> if (value is JsonPrimitive) value.content else value.toString()
                else -> value.toString()
            },
        )
    }

    fun event(data: Map<String, String>, includeRaw: Boolean): Map<String, String> =
        data.mapValues { (key, value) ->
            when {
                secretKey.containsMatchIn(key) -> "***"
                includeRaw -> value.take(MAX_VALUE)
                else -> "***"
            }
        }
}
