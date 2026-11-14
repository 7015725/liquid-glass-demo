package com.flowglass.demo.ui

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
                spec == meta.params.firstOrNull() -> parameter
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
}
