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
 *
 * Template literals follow JavaScript semantics: the text between backticks is data, but
 * `${ ... }` is code and goes through the same token pass — including nested templates and
 * object literals inside the expression.
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
        // Brace depth of each active `${ ... }`; a template nested inside an expression pushes
        // another entry, so the closing braces unwind in the right order.
        val expressionDepth = ArrayDeque<Int>()
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
                State.SINGLE, State.DOUBLE -> {
                    val quote = if (state == State.SINGLE) '\'' else '"'
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
                State.TEMPLATE -> {
                    if (char == '\\' && index + 1 < source.length) {
                        out.append(char).append(source[index + 1])
                        index += 2
                        continue
                    }
                    if (char == '`') {
                        state = State.CODE
                        out.append(char)
                        index++
                        continue
                    }
                    if (char == '$' && index + 1 < source.length && source[index + 1] == '{') {
                        out.append('$').append('{')
                        index += 2
                        expressionDepth.addLast(1)
                        state = State.CODE
                        continue
                    }
                    out.append(char)
                    index++
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
                    val literal = VariableToken.literal(source, index)
                    if (literal != null) {
                        out.append(literal.first)
                        index = literal.second
                        continue
                    }
                    val token = VariableToken.at(source, index)
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
                        continue
                    }
                    // Braces that belong to the surrounding `${ ... }`. Braces written as a
                    // variable token were already consumed above and never change the depth.
                    if (expressionDepth.isNotEmpty() && char == '{') {
                        expressionDepth.addLast(expressionDepth.removeLast() + 1)
                        out.append(char)
                        index++
                        continue
                    }
                    if (expressionDepth.isNotEmpty() && char == '}') {
                        val depth = expressionDepth.removeLast() - 1
                        if (depth == 0) {
                            state = State.TEMPLATE
                        } else {
                            expressionDepth.addLast(depth)
                        }
                        out.append(char)
                        index++
                        continue
                    }
                    out.append(char)
                    index++
                }
            }
        }
        return PreparedLanguageScript(out.toString(), generated)
    }

    private enum class State { CODE, SINGLE, DOUBLE, TEMPLATE, LINE_COMMENT, BLOCK_COMMENT }
}
