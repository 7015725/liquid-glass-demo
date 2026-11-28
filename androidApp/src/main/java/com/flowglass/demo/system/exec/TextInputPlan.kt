package com.flowglass.demo.system.exec

/**
 * Pure text-insertion helpers shared by the accessibility path and its Root fallback.
 * Keeping the cursor math and shell encoding here makes both deterministic and unit-testable.
 */
internal object TextInputPlan {
    /**
     * Insert [text] at the current selection without changing the surrounding content.
     * Negative or out-of-range selections fail closed to an append/whole-field replacement
     * rather than throwing inside the accessibility callback.
     */
    fun insert(existing: String, selectionStart: Int, selectionEnd: Int, text: String): String {
        val start = selectionStart.coerceIn(0, existing.length).let { candidate ->
            if (selectionStart < 0) existing.length else candidate
        }
        val end = selectionEnd.coerceIn(0, existing.length).let { candidate ->
            if (selectionEnd < 0) existing.length else candidate
        }
        val from = minOf(start, end)
        val to = maxOf(start, end)
        return existing.substring(0, from) + text + existing.substring(to)
    }

    /**
     * Root `input text` can only synthesize printable ASCII. Chinese and other Unicode text
     * must use the accessibility path, so callers get null instead of a command that silently
     * types nothing.
     */
    fun rootCommand(text: String): String? {
        if (text.isEmpty() || text.any { it.code !in 0x20..0x7E }) return null
        // `input text` turns the two-character token %s into a literal space. Passing the text
        // as a quoted argument alone is not enough because the command consumes only one arg.
        val encoded = text.replace(" ", "%s")
        return "input text " + shellQuote(encoded)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
