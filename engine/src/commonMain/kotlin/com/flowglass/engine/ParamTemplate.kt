package com.flowglass.engine

import kotlinx.serialization.json.*

/**
 * `%{name}` is expanded when a parameter is read, not when the task is saved, so the stored
 * value stays stable while one step can still feed the next. An explicit `%{name}` token is
 * used so ordinary text containing a bare `%` stays untouched.
 *
 * Expansion is context-sensitive: [ParamSpec.expansion] decides whether a substituted value is
 * inserted verbatim, as one POSIX shell literal, or percent-encoded for a URL. Unknown names
 * stay literal (a typo should be visible, not silently empty), and expansion is single-pass:
 * a value that itself contains `%{x}` is not expanded again, so a variable can never expand
 * into itself.
 */
object ParamTemplate {
    private val token = Regex("""%\{([A-Za-z0-9_.\-]+)}""")
    private const val HEX = "0123456789ABCDEF"

    fun expand(
        text: String,
        variables: Map<String, Any?>,
        expansion: ParamExpansion = ParamExpansion.PLAIN,
    ): String {
        if (variables.isEmpty() || !text.contains("%{")) return text
        return when (expansion) {
            ParamExpansion.PLAIN -> replace(text, variables, ::identity)
            ParamExpansion.SHELL_QUOTED -> replace(text, variables, ::shellQuote)
            ParamExpansion.URL_ENCODED -> replace(text, variables, ::percentEncode)
            ParamExpansion.HTTP_REQUEST -> expandHttpRequest(text, variables)
        }
    }

    /** Applies each parameter's declared [ParamSpec.expansion]; unknown keys stay plain text. */
    fun expand(
        params: Map<String, JsonElement>,
        specs: List<ParamSpec>,
        variables: Map<String, Any?>,
    ): Map<String, JsonElement> {
        if (variables.isEmpty() || params.isEmpty()) return params
        val expansions = specs.associate { it.key to it.expansion }
        return params.mapValues { (key, value) ->
            expandElement(value, variables, expansions[key] ?: ParamExpansion.PLAIN)
        }
    }

    fun expand(params: Map<String, JsonElement>, variables: Map<String, Any?>): Map<String, JsonElement> =
        expand(params, emptyList(), variables)

    private fun replace(
        text: String,
        variables: Map<String, Any?>,
        transform: (String) -> String,
    ): String = token.replace(text) { match ->
        val value = variables[match.groupValues[1]]
        if (value == null) match.value else transform(value.toString())
    }

    private fun expandHttpRequest(text: String, variables: Map<String, Any?>): String {
        val methodStart = text.indexOfFirst { !it.isWhitespace() }
        if (methodStart < 0) return expand(text, variables, ParamExpansion.PLAIN)
        val methodEnd = text.indexOfFirstFrom(methodStart) { it.isWhitespace() }
        if (methodEnd < 0) return expand(text, variables, ParamExpansion.PLAIN)
        val urlStart = text.indexOfFirstFrom(methodEnd) { !it.isWhitespace() }
        if (urlStart < 0) return expand(text, variables, ParamExpansion.PLAIN)
        val urlEnd = text.indexOfFirstFrom(urlStart) { it.isWhitespace() }
            .let { if (it < 0) text.length else it }

        // Only the URL segment is encoded. The prefix (method) and the body are plain text,
        // so a POST body that contains `&`, `#`, newlines or `%{...}` is sent unchanged.
        return expand(text.substring(0, urlStart), variables, ParamExpansion.PLAIN) +
            expand(text.substring(urlStart, urlEnd), variables, ParamExpansion.URL_ENCODED) +
            expand(text.substring(urlEnd), variables, ParamExpansion.PLAIN)
    }

    private fun identity(value: String): String = value

    /**
     * Wraps one value in POSIX single quotes. A single quote inside the value is emitted as
     * `'"'"'`, which closes the quote, adds an escaped quote, and reopens it. Shell syntax
     * (`;`, `&&`, `|`, `$()`, backticks, newlines) therefore stays literal data.
     */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /** UTF-8 percent encoding for one URL component; unreserved characters stay as-is. */
    private fun percentEncode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '.' || char == '_' || char == '~'
            ) {
                out.append(char)
            } else {
                out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
        return out.toString()
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return -1
    }

    private fun expandElement(
        element: JsonElement,
        variables: Map<String, Any?>,
        expansion: ParamExpansion,
    ): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) JsonPrimitive(expand(element.content, variables, expansion)) else element
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> expandElement(value, variables, expansion) })
        is JsonArray -> JsonArray(element.map { expandElement(it, variables, expansion) })
    }
}
