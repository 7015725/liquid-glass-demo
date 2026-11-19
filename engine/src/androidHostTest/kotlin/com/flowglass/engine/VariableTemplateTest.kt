package com.flowglass.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariableTemplateTest {
    @Test
    fun expandsNewChineseName() {
        assertEquals(
            "你好，世界",
            ParamTemplate.expand("你好，{名字}", mapOf("名字" to "世界")),
        )
    }

    @Test
    fun doubledBraceIsLiteral() {
        assertEquals("{名字}", ParamTemplate.expand("{{名字}}", mapOf("名字" to "值")))
        assertEquals("前缀 {名字} 后缀", ParamTemplate.expand("前缀 {{名字}} 后缀", mapOf("名字" to "值")))
    }

    @Test
    fun unknownNewNameStaysLiteral() {
        assertEquals("你好 {不存在}", ParamTemplate.expand("你好 {不存在}", emptyMap()))
    }

    @Test
    fun jsonBracesAreNotVariables() {
        assertEquals(
            """{"名字":"值","count":1}""",
            ParamTemplate.expand("""{"名字":"值","count":1}""", mapOf("名字" to "替换")),
        )
    }

    @Test
    fun shellParameterExpansionIsNotAVariable() {
        assertEquals(
            "${'$'}{HOME} {名字}",
            ParamTemplate.expand("${'$'}{HOME} {名字}", emptyMap()),
        )
    }

    @Test
    fun legacyAndNewSyntaxAreEquivalent() {
        val values = mapOf("last" to "完成", "上一步结果" to "完成")
        assertEquals(ParamTemplate.expand("%{last}", values), ParamTemplate.expand("{上一步结果}", values))
        assertEquals("完成", ParamTemplate.expand("{上一步结果}", values))
        assertEquals("完成", ParamTemplate.expand("%{last}", values))
    }

    @Test
    fun shellUsesPreludeForNewSyntax() {
        val value = "a'; touch /tmp/pwn; echo '`whoami`"
        val expanded = ParamTemplate.expand("echo {名字}", mapOf("名字" to value), ParamExpansion.SHELL_QUOTED)
        assertTrue(expanded.startsWith("FG_V1="))
        val body = expanded.substringAfter('\n')
        assertTrue(body.contains("FG_V1"))
        assertFalse(body.contains("touch"))
    }

    @Test
    fun variableNameRules() {
        assertTrue(VariableName.isValid("上一步结果"))
        assertTrue(VariableName.isValid("foo_bar-1"))
        assertFalse(VariableName.isValid(""))
        assertFalse(VariableName.isValid("a b"))
        assertFalse(VariableName.isValid("a.b"))
        assertFalse(VariableName.isValid("1abc"))
        assertFalse(VariableName.isValid("a".repeat(VariableName.MAX_LENGTH + 1)))
        assertEquals("名字已被占用", "名字已被占用")
    }

    @Test
    fun builtInCatalogKeepsOnlySystemValues() {
        assertEquals("上一步结果", BuiltInVariables.byLegacyKey["last"]?.name)
        assertEquals(listOf("上一步结果"), BuiltInVariables.all.map { it.name })
        assertEquals(null, BuiltInVariables.byLegacyKey["http.body"])
        assertEquals(null, BuiltInVariables.byName["通知标题"])
    }
}
