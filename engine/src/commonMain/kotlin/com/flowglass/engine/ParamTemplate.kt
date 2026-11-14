package com.flowglass.engine

import kotlinx.serialization.json.*

/**
 * `%{name}` is expanded when a parameter is read, not when the task is saved, so the stored
 * value stays stable while one step can still feed the next. An explicit `%{name}` token is
 * used so ordinary text containing a bare `%` stays untouched.
 *
 * Expansion is context-sensitive: [ParamSpec.expansion] decides whether a substituted value is
 * inserted verbatim, assigned to a shell variable, or percent-encoded for a URL. Unknown names
 * stay literal (a typo should be visible, not silently empty), and expansion is single-pass:
 * a value that itself contains `%{x}` is not expanded again, so a variable can never expand
 * into itself.
 *
 * Token recognition is a hand-written scan rather than a [Regex]: Android's ICU engine treats an
 * unescaped literal brace as a syntax error, while the JVM engine used by unit tests accepts it.
 */
object ParamTemplate {
    private const val HEX = "0123456789ABCDEF"

    fun expand(
        text: String,
        variables: Map<String, Any?>,
        expansion: ParamExpansion = ParamExpansion.PLAIN,
    ): String {
        if (variables.isEmpty() || !text.contains("%{")) return text
        return when (expansion) {
            ParamExpansion.PLAIN -> replace(text, variables, ::identity)
            ParamExpansion.SHELL_QUOTED -> expandShell(text, variables)
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
    ): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val end = tokenEnd(text, index)
            if (end != null) {
                val value = variables[text.substring(index + 2, end)]
                if (value == null) out.append(text, index, end + 1)
                else out.append(transform(value.toString()))
                index = end + 1
            } else {
                out.append(text[index])
                index++
            }
        }
        return out.toString()
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
     * Shell values never appear in the user's command text. Each referenced value becomes a
     * single-quoted assignment in a generated prelude (`FG_V1='...'`); the user command only
     * contains a variable reference. Even if the quote scanner misclassifies a construct, the
     * worst case is an extra quote or a different word split, never re-parsing the value as
     * command syntax.
     *
     * The scanner still tracks quotes (and unquoted heredocs) to choose the reference spelling:
     * unquoted `"${FG_V1}"`, already double-quoted `${FG_V1}`, single-quoted `'"${FG_V1}"'`,
     * or heredoc body `${FG_V1}`. Names are allocated as FG_V1, FG_V2, ... so a user variable
     * cannot overwrite PATH or another environment name.
     */
    private fun expandShell(text: String, variables: Map<String, Any?>): String {
        val names = LinkedHashMap<String, String>()

        fun fgName(name: String): String? {
            if (variables[name] == null) return null
            return names.getOrPut(name) { "FG_V${names.size + 1}" }
        }

        fun reference(fg: String, quote: ShellQuote): String {
            val ref = "\${$fg}"
            return when (quote) {
                ShellQuote.NONE -> "\"$ref\""
                ShellQuote.DOUBLE -> ref
                ShellQuote.SINGLE -> "'\"$ref\"'"
            }
        }

        fun expandHeredocLine(line: String): String {
            val out = StringBuilder(line.length)
            var index = 0
            while (index < line.length) {
                val end = tokenEnd(line, index)
                if (end != null) {
                    val name = line.substring(index + 2, end)
                    val fg = fgName(name)
                    if (fg == null) out.append(line, index, end + 1)
                    else out.append("\${").append(fg).append('}')
                    index = end + 1
                } else {
                    out.append(line[index])
                    index++
                }
            }
            return out.toString()
        }

        fun consumeHeredoc(start: Int, heredoc: Heredoc): Consumed {
            val out = StringBuilder()
            var index = start
            while (true) {
                val newline = text.indexOf('\n', index)
                val lineEnd = if (newline < 0) text.length else newline
                val line = text.substring(index, lineEnd)
                val compared = if (heredoc.stripTabs) line.trimStart('\t') else line
                if (compared == heredoc.delimiter) {
                    out.append(line)
                    if (newline < 0) return Consumed(out.toString(), text.length)
                    out.append('\n')
                    return Consumed(out.toString(), newline + 1)
                }
                out.append(if (heredoc.quoted) line else expandHeredocLine(line))
                if (newline < 0) return Consumed(out.toString(), text.length)
                out.append('\n')
                index = newline + 1
            }
        }

        fun parseHeredoc(start: Int): HeredocStart? {
            var index = start + 2
            var stripTabs = false
            if (index < text.length && text[index] == '-') {
                stripTabs = true
                index++
            }
            while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
            if (index >= text.length) return null
            val quoted: Boolean
            val delimiter: String
            if (text[index] == '\'' || text[index] == '"') {
                val quoteChar = text[index++]
                val begin = index
                while (index < text.length && text[index] != quoteChar) index++
                if (index >= text.length) return null
                delimiter = text.substring(begin, index)
                quoted = true
                index++
            } else {
                val begin = index
                while (index < text.length && !text[index].isWhitespace() &&
                    text[index] != ';' && text[index] != '|' && text[index] != '&' &&
                    text[index] != '(' && text[index] != ')' && text[index] != '<' && text[index] != '>'
                ) {
                    index++
                }
                if (index == begin) return null
                delimiter = text.substring(begin, index)
                quoted = false
            }
            if (delimiter.isEmpty()) return null
            return HeredocStart(index, Heredoc(delimiter, quoted, stripTabs))
        }

        val out = StringBuilder(text.length)
        var index = 0
        var quote = ShellQuote.NONE
        var pendingHeredoc: Heredoc? = null
        val quoteStack = ArrayDeque<ShellQuote>()
        var parenDepth = 0
        var inBacktick = false
        while (index < text.length) {
            val end = tokenEnd(text, index)
            if (end != null) {
                val name = text.substring(index + 2, end)
                val fg = fgName(name)
                if (fg == null) out.append(text, index, end + 1)
                else out.append(reference(fg, quote))
                index = end + 1
                continue
            }
            val char = text[index]
            if (quote == ShellQuote.NONE && char == '<' && index + 1 < text.length &&
                text[index + 1] == '<' && isHeredocPosition(text, index)
            ) {
                val heredoc = parseHeredoc(index)
                if (heredoc != null) {
                    out.append(text, index, heredoc.end)
                    pendingHeredoc = heredoc.heredoc
                    index = heredoc.end
                    continue
                }
            }
            if (quote == ShellQuote.NONE && char == '\n' && pendingHeredoc != null) {
                out.append('\n')
                val consumed = consumeHeredoc(index + 1, pendingHeredoc)
                out.append(consumed.text)
                index = consumed.end
                pendingHeredoc = null
                continue
            }
            // `$(...)` and backticks open a fresh command context. A token there is not
            // inside the surrounding double quotes for word-splitting purposes, so it must
            // use a quoted reference too.
            if (quote != ShellQuote.SINGLE && !inBacktick && char == '$' &&
                index + 1 < text.length && text[index + 1] == '('
            ) {
                quoteStack.addLast(quote)
                quote = ShellQuote.NONE
                parenDepth++
                out.append("$(")
                index += 2
                continue
            }
            if (quote != ShellQuote.SINGLE) {
                if (inBacktick) {
                    if (char == '`') {
                        quote = quoteStack.removeLast()
                        inBacktick = false
                        out.append(char)
                        index++
                        continue
                    }
                } else if (char == '`') {
                    quoteStack.addLast(quote)
                    quote = ShellQuote.NONE
                    inBacktick = true
                    out.append(char)
                    index++
                    continue
                }
                if (parenDepth > 0 && quote == ShellQuote.NONE) {
                    when (char) {
                        '(' -> {
                            parenDepth++
                            out.append(char)
                            index++
                            continue
                        }
                        ')' -> {
                            parenDepth--
                            if (parenDepth == 0) quote = quoteStack.removeLast()
                            out.append(char)
                            index++
                            continue
                        }
                    }
                }
            }
            when (quote) {
                ShellQuote.NONE -> when {
                    char == '\\' && index + 1 < text.length -> {
                        out.append(char).append(text[index + 1])
                        index += 2
                        continue
                    }
                    char == '\'' -> quote = ShellQuote.SINGLE
                    char == '"' -> quote = ShellQuote.DOUBLE
                }
                ShellQuote.SINGLE -> if (char == '\'') quote = ShellQuote.NONE
                ShellQuote.DOUBLE -> when {
                    char == '\\' && index + 1 < text.length -> {
                        out.append(char).append(text[index + 1])
                        index += 2
                        continue
                    }
                    char == '"' -> quote = ShellQuote.NONE
                }
            }
            out.append(char)
            index++
        }
        if (names.isEmpty()) return text
        val prefix = StringBuilder()
        for ((name, fg) in names) {
            prefix.append(fg).append('=').append(shellQuote(variables.getValue(name).toString())).append('\n')
        }
        return prefix.toString() + out.toString()
    }

    private fun isHeredocPosition(text: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = text[index - 1]
        return previous.isWhitespace() || previous == ';' || previous == '|' ||
            previous == '&' || previous == '(' || previous == ')'
    }

    private fun tokenEnd(text: String, start: Int): Int? {
        if (start + 2 >= text.length || text[start] != '%' || text[start + 1] != '{') return null
        var index = start + 2
        while (index < text.length && text[index] != '}') {
            val char = text[index]
            if (char !in 'A'..'Z' && char !in 'a'..'z' && char !in '0'..'9' &&
                char != '.' && char != '_' && char != '-'
            ) {
                return null
            }
            index++
        }
        if (index >= text.length || index == start + 2) return null
        return index
    }

    private enum class ShellQuote { NONE, SINGLE, DOUBLE }
    private data class Heredoc(val delimiter: String, val quoted: Boolean, val stripTabs: Boolean)
    private data class HeredocStart(val end: Int, val heredoc: Heredoc)
    private data class Consumed(val text: String, val end: Int)

    /**
     * Wraps one value in POSIX single quotes. A single quote inside the value is emitted as
     * `'\"'\"'`, which closes the quote, adds an escaped quote, and reopens it. This is used
     * only for the generated assignment prelude, whose surrounding context is fully controlled.
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
