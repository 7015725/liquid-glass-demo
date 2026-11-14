package com.flowglass.engine

import kotlinx.serialization.json.*

/**
 * Tasker expands `%vars` when a parameter is read, not when the task is saved; this keeps the
 * stored value stable while one step can feed the next. The same rule applies here with an
 * explicit `%{name}` token so ordinary text containing a bare `%` stays untouched.
 *
 * Unknown names stay literal (a typo should be visible, not silently empty), and expansion is
 * single-pass: a value that itself contains `%{x}` is not expanded again, so a variable can
 * never expand into itself.
 */
object ParamTemplate {
    private val token = Regex("""%\{([A-Za-z0-9_.\-]+)}""")

    fun expand(text: String, variables: Map<String, Any?>): String {
        if (variables.isEmpty() || !text.contains("%{")) return text
        return token.replace(text) { match ->
            val value = variables[match.groupValues[1]]
            value?.toString() ?: match.value
        }
    }

    fun expand(params: Map<String, JsonElement>, variables: Map<String, Any?>): Map<String, JsonElement> {
        if (variables.isEmpty() || params.isEmpty()) return params
        return params.mapValues { (_, value) -> expandElement(value, variables) }
    }

    private fun expandElement(element: JsonElement, variables: Map<String, Any?>): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) JsonPrimitive(expand(element.content, variables)) else element
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> expandElement(value, variables) })
        is JsonArray -> JsonArray(element.map { expandElement(it, variables) })
    }
}
