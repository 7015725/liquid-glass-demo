package com.flowglass.demo.system.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInputPlanTest {
    @Test fun insertsAtCaretAndReplacesSelection() {
        assertEquals("hello world", TextInputPlan.insert("hello", 5, 5, " world"))
        assertEquals("hello there", TextInputPlan.insert("hello", 5, 5, " there"))
        assertEquals("hello brave world", TextInputPlan.insert("hello world", 6, 11, "brave world"))
        assertEquals("ho", TextInputPlan.insert("hello", 1, 4, ""))
    }

    @Test fun invalidSelectionsAppendInsteadOfThrowing() {
        assertEquals("hello!", TextInputPlan.insert("hello", -1, -1, "!"))
        assertEquals("hello!", TextInputPlan.insert("hello", 99, 99, "!"))
        assertEquals("he!o", TextInputPlan.insert("hello", 4, 2, "!"))
    }

    @Test fun rootCommandEncodesSpacesAndQuotesTheArgument() {
        assertEquals("input text 'hello%sworld'", TextInputPlan.rootCommand("hello world"))
        assertEquals("input text 'it'\\''s'", TextInputPlan.rootCommand("it's"))
        assertEquals("input text 'ASCII-123'", TextInputPlan.rootCommand("ASCII-123"))
    }

    @Test fun rootCommandRejectsUnicodeAndControlCharacters() {
        assertNull(TextInputPlan.rootCommand("中文"))
        assertNull(TextInputPlan.rootCommand("line\nbreak"))
        assertNull(TextInputPlan.rootCommand(""))
        assertTrue(TextInputPlan.rootCommand("plain text") != null)
    }
}
