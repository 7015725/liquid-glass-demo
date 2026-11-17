package com.flowglass.engine

/** One value offered to a code field. The value is data, never source text. */
data class LanguageBinding(val name: String, val value: Any?)

/**
 * A code field prepared for one language. [bindings] are injected as read-only values by the
 * host; [source] only contains generated identifiers (or shell references), never user values.
 */
data class PreparedLanguageScript(
    val source: String,
    val bindings: Map<String, Any?> = emptyMap(),
)

/**
 * Contract for future script languages. A plugin must:
 *  - resolve `{名字}` to a safe reference without concatenating the value into source;
 *  - expose the supplied bindings as read-only data;
 *  - let the host implement `get` / `set` through [ScriptVariableAccess].
 *
 * Shell and JavaScript are the only implementations in this release.
 */
interface ScriptLanguage {
    val id: String

    fun prepare(source: String, bindings: List<LanguageBinding>): PreparedLanguageScript
}

/** Shell is already protected by the existing FG_Vn assignment prelude. */
object ShellLanguage : ScriptLanguage {
    override val id: String = "shell"

    override fun prepare(source: String, bindings: List<LanguageBinding>): PreparedLanguageScript {
        val values = bindings.associate { it.name to it.value }
        return PreparedLanguageScript(ParamTemplate.expand(source, values, ParamExpansion.SHELL_QUOTED))
    }
}

/**
 * JavaScript binding pass. It scans code, skips strings and comments (so JSON examples and
 * `{...}` in strings are untouched), and replaces recognised tokens with generated identifiers.
 * The actual values live in [PreparedLanguageScript.bindings] and are installed read-only by Rhino.
 */
object JavaScriptLanguage : ScriptLanguage {
    override val id: String = "javascript"

    override fun prepare(source: String, bindings: List<LanguageBinding>): PreparedLanguageScript {
        val known = bindings.associateBy { it.name }
        val generated = linkedMapOf<String, Any?>()
        val identifierFor = mutableMapOf<String, String>()
        val out = StringBuilder(source.length)
        var index = 0
        var state = State.CODE
        while (index < source.length) {
            val char = source[index]
            when (state) {
                State.LINE_COMMENT -> {
                    out.append(char)
                    index++
                    if (char == '\n') state = State.CODE
                    continue
                }
                State.BLOCK_COMMENT -> {
                    out.append(char)
                    index++
                    if (char == '*' && index < source.length && source[index] == '/') {
                        out.append('/')
                        index++
                        state = State.CODE
                    }
                    continue
                }
                State.SINGLE, State.DOUBLE, State.TEMPLATE -> {
                    val quote = when (state) {
                        State.SINGLE -> '\''
                        State.DOUBLE -> '"'
                        else -> '`'
                    }
                    out.append(char)
                    index++
                    if (char == '\\' && index < source.length) {
                        out.append(source[index])
                        index++
                    } else if (char == quote) {
                        state = State.CODE
                    }
                    continue
                }
                State.CODE -> Unit
            }
            when {
                char == '/' && index + 1 < source.length && source[index + 1] == '/' -> {
                    state = State.LINE_COMMENT
                    out.append("//")
                    index += 2
                }
                char == '/' && index + 1 < source.length && source[index + 1] == '*' -> {
                    state = State.BLOCK_COMMENT
                    out.append("/*")
                    index += 2
                }
                char == '\'' -> { state = State.SINGLE; out.append(char); index++ }
                char == '"' -> { state = State.DOUBLE; out.append(char); index++ }
                char == '`' -> { state = State.TEMPLATE; out.append(char); index++ }
                else -> {
                    val literal = literalBrace(source, index)
                    if (literal != null) {
                        out.append(literal.first)
                        index = literal.second
                        continue
                    }
                    val token = tokenAt(source, index)
                    if (token != null) {
                        val value = known[token.name]
                        if (value == null) {
                            out.append(source, index, token.end + 1)
                        } else {
                            val identifier = identifierFor.getOrPut(token.name) {
                                var candidate = "__fg${identifierFor.size}"
                                while (source.contains(candidate)) candidate += "_"
                                generated[candidate] = value.value
                                candidate
                            }
                            out.append(identifier)
                        }
                        index = token.end + 1
                    } else {
                        out.append(char)
                        index++
                    }
                }
            }
        }
        return PreparedLanguageScript(out.toString(), generated)
    }

    private fun tokenAt(text: String, start: Int): Token? {
        if (start + 2 < text.length && text[start] == '%' && text[start + 1] == '{') {
            var index = start + 2
            while (index < text.length && text[index] != '}') {
                val char = text[index]
                if (char !in 'A'..'Z' && char !in 'a'..'z' && char !in '0'..'9' &&
                    char != '.' && char != '_' && char != '-'
                ) return null
                index++
            }
            if (index >= text.length || index == start + 2) return null
            return Token(text.substring(start + 2, index), index)
        }
        if (start + 1 < text.length && text[start] == '{' && text[start + 1] != '{') {
            var index = start + 1
            while (index < text.length && text[index] != '}') {
                val char = text[index]
                if (!char.isLetterOrDigit() && char != '_' && char != '-') return null
                index++
            }
            if (index >= text.length || index == start + 1) return null
            val name = text.substring(start + 1, index)
            if (!VariableName.isValid(name)) return null
            return Token(name, index)
        }
        return null
    }

    private fun literalBrace(text: String, start: Int): Pair<String, Int>? {
        if (start + 3 >= text.length || text[start] != '{' || text[start + 1] != '{') return null
        val close = text.indexOf("}}", start + 2)
        if (close < 0) return null
        val name = text.substring(start + 2, close)
        if (!VariableName.isValid(name)) return null
        return "{$name}" to close + 2
    }

    private data class Token(val name: String, val end: Int)
    private enum class State { CODE, SINGLE, DOUBLE, TEMPLATE, LINE_COMMENT, BLOCK_COMMENT }
}
