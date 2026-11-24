package com.flowglass.engine

/**
 * Recognition of one `{名字}` / `{global.名字}` / `%{...}` token.
 *
 * Parameter expansion and the JavaScript binding pass used to carry separate copies of this
 * scanner; keeping one implementation is what stops the two languages from disagreeing about
 * which names are variables. Scoped prefixes are accepted with Chinese names (`{global.城市}`)
 * because the editor's variable picker inserts names in that alphabet; the legacy `%{...}`
 * spelling follows the same grammar, while an unknown prefix (`%{http.body}`) stays literal.
 */
internal object VariableToken {
    private val scopes = setOf("rule", "global", "run", "event")

    data class Token(val name: String, val end: Int)

    fun at(text: String, start: Int): Token? {
        if (start + 2 < text.length && text[start] == '%' && text[start + 1] == '{') {
            var index = start + 2
            while (index < text.length && text[index] != '}') {
                val char = text[index]
                if (!char.isLetterOrDigit() && char != '_' && char != '-' && char != '.') return null
                index++
            }
            if (index >= text.length || index == start + 2) return null
            val name = text.substring(start + 2, index)
            if (!isValidName(name)) return null
            return Token(name, index)
        }
        if (start + 1 < text.length && text[start] == '{' && text[start + 1] != '{') {
            // `${VAR}` is shell parameter expansion, and `${...}` in JavaScript is an expression.
            if (start > 0 && text[start - 1] == '$') return null
            var index = start + 1
            while (index < text.length && text[index] != '}') {
                val char = text[index]
                if (!char.isLetterOrDigit() && char != '_' && char != '-' && char != '.') return null
                index++
            }
            if (index >= text.length || index == start + 1) return null
            val name = text.substring(start + 1, index)
            if (!isValidName(name)) return null
            return Token(name, index)
        }
        return null
    }

    /** `{{名字}}` is an escaped literal; scoped names follow the same grammar. */
    fun literal(text: String, start: Int): Pair<String, Int>? {
        if (start + 3 >= text.length || text[start] != '{' || text[start + 1] != '{') return null
        val close = text.indexOf("}}", start + 2)
        if (close < 0) return null
        val name = text.substring(start + 2, close)
        if (!isValidName(name)) return null
        return "{$name}" to close + 2
    }

    fun isValidName(raw: String): Boolean {
        if (VariableName.isValid(raw)) return true
        val dot = raw.indexOf('.')
        if (dot <= 0 || dot == raw.length - 1) return false
        if (raw.substring(0, dot) !in scopes) return false
        return VariableName.isValid(raw.substring(dot + 1))
    }
}
