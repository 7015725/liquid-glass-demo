package com.flowglass.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguagePluginTest {
    private val dangerous = "a'b\"c$(touch /tmp/pwned)`id`"

    @Test
    fun javascriptBindsChineseNamesWithoutSplicingValues() {
        val prepared = JavaScriptLanguage.prepare(
            "var value = {名字}; value;",
            listOf(LanguageBinding("名字", dangerous)),
        )
        assertEquals("var value = __fg0; value;", prepared.source)
        assertEquals(dangerous, prepared.bindings.getValue("__fg0"))
        assertFalse(prepared.source.contains("touch"))
        assertFalse(prepared.source.contains("pwned"))
    }

    @Test
    fun javascriptLeavesJsonAndUnknownBracesAlone() {
        val prepared = JavaScriptLanguage.prepare(
            "var json = {\"a\": 1}; var missing = {不存在};",
            listOf(LanguageBinding("名字", "x")),
        )
        assertEquals("var json = {\"a\": 1}; var missing = {不存在};", prepared.source)
        assertTrue(prepared.bindings.isEmpty())
    }

    @Test
    fun javascriptTurnsEscapedBracesIntoLliterals() {
        val prepared = JavaScriptLanguage.prepare(
            "var text = {{名字}};",
            listOf(LanguageBinding("名字", dangerous)),
        )
        assertEquals("var text = {名字};", prepared.source)
    }

    @Test
    fun shellPreparesAQuotedAssignmentPrelude() {
        val prepared = ShellLanguage.prepare(
            "echo {名字}",
            listOf(LanguageBinding("名字", dangerous)),
        )
        assertTrue(prepared.source.startsWith("FG_V1="))
        assertTrue(prepared.source.contains("echo \"${'$'}{FG_V1}\""), prepared.source)
        assertEquals(emptyMap(), prepared.bindings)
    }

    @Test
    fun bothLanguagesShareTheSameBindingContract() {
        val bindings = listOf(
            LanguageBinding("中文", "第一行\n第二行"),
            LanguageBinding("数字", 42L),
            LanguageBinding("真假", true),
        )
        for (language in listOf<ScriptLanguage>(ShellLanguage, JavaScriptLanguage)) {
            val prepared = language.prepare("a={中文};b={数字};c={真假};", bindings)
            assertTrue(prepared.source.contains("第一行") || prepared.source.contains("__fg"))
            assertFalse(prepared.source.contains("\n第二行") && language.id == "javascript")
        }
    }
}
