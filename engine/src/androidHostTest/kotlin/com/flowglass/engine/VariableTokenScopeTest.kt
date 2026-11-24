package com.flowglass.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 参数里的作用域写法：`{global.名字}` / `{rule.名字}` 必须和 `get()` 一样可用，中文名不能被
 * 旧前缀的 ASCII 限制挡住；未认识的前缀（如旧输出 key `http.body`）必须保持字面量，不能因为
 * 放宽点号就误解析。
 */
class VariableTokenScopeTest {
    private val values = mapOf<String, Any?>(
        "城市" to "上海",
        "global.城市" to "北京",
        "rule.城市" to "广州",
        "run.城市" to "深圳",
        "event.城市" to "杭州",
        "http.body" to "响应",
        "global.城市.区" to "朝阳",
    )

    @Test
    fun globalPrefixReachesTheGlobalWithAChineseName() {
        assertEquals("北京", ParamTemplate.expand("{global.城市}", values))
    }

    @Test
    fun rulePrefixReachesTheLocal() {
        assertEquals("广州", ParamTemplate.expand("{rule.城市}", values))
    }

    @Test
    fun runAndEventPrefixesKeepWorking() {
        assertEquals("深圳", ParamTemplate.expand("{run.城市}", values))
        assertEquals("杭州", ParamTemplate.expand("{event.城市}", values))
    }

    @Test
    fun bareNameKeepsItsResolutionOrder() {
        assertEquals("上海", ParamTemplate.expand("{城市}", values))
    }

    @Test
    fun unrecognisedPrefixStaysLiteral() {
        assertEquals("{http.body}", ParamTemplate.expand("{http.body}", values))
    }

    @Test
    fun aSecondDotIsNotAScopedName() {
        assertEquals("{global.城市.区}", ParamTemplate.expand("{global.城市.区}", values))
    }

    @Test
    fun escapedScopedTokenStaysLiteral() {
        assertEquals("{global.城市}", ParamTemplate.expand("{{global.城市}}", values))
    }

    @Test
    fun legacyPercentSyntaxAlsoAcceptsTheScopedName() {
        assertEquals("北京", ParamTemplate.expand("%{global.城市}", values))
        assertEquals("上海", ParamTemplate.expand("%{城市}", values))
    }

    @Test
    fun shellExpansionTreatsAScopedNameAsOneValue() {
        val expanded = ParamTemplate.expand(
            "printf '%s' \"{global.城市}|{rule.城市}\"",
            values,
            ParamExpansion.SHELL_QUOTED,
        )

        assertEquals(
            "FG_V1='北京'\nFG_V2='广州'\nprintf '%s' \"\${FG_V1}|\${FG_V2}\"",
            expanded,
        )
    }
}
