package com.flowglass.demo.system.exec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads the JSON form written by ParameterCodec; falls back to raw text for hand-edited rules. */
internal object JavaCodeParameters {
    const val DEFAULT_TIMEOUT_SECONDS = 30L
    const val MAX_TIMEOUT_SECONDS = 120L

    fun code(parameter: String): String =
        (structured(parameter)?.get("code") as? JsonPrimitive)?.content ?: parameter

    fun timeoutSeconds(parameter: String): Long =
        (structured(parameter)?.get("timeout") as? JsonPrimitive)?.content?.toLongOrNull()
            ?.coerceIn(1L, MAX_TIMEOUT_SECONDS) ?: DEFAULT_TIMEOUT_SECONDS

    private fun structured(parameter: String): JsonObject? {
        val text = parameter.trimStart()
        if (!text.startsWith("{")) return null
        return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }
}
