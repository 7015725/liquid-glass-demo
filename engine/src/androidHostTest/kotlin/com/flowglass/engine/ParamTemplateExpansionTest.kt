package com.flowglass.engine

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ParamTemplateExpansionTest {
    @Test
    fun plainExpansionKeepsTheValueVerbatim() {
        val payload = "a; b && c | d $(e) `f`\n'\""

        assertEquals("before $payload after", ParamTemplate.expand(
            "before %{value} after",
            mapOf("value" to payload),
            ParamExpansion.PLAIN,
        ))
    }

    @Test
    fun shellExpansionAssignsTheValueAndReferencesItFromTheCommand() {
        assertEquals(
            "FG_V1='a'\"'\"'b; c'\necho \"\${FG_V1}\"",
            ParamTemplate.expand(
                "echo %{value}",
                mapOf("value" to "a'b; c"),
                ParamExpansion.SHELL_QUOTED,
            ),
        )
    }

    @Test
    fun shellExpansionUsesOneReferenceSpellingPerQuoteContext() {
        val variables = mapOf("value" to "a'b")

        assertEquals(
            "FG_V1='a'\"'\"'b'\necho \"\${FG_V1}\"",
            ParamTemplate.expand("echo %{value}", variables, ParamExpansion.SHELL_QUOTED),
        )
        assertEquals(
            "FG_V1='a'\"'\"'b'\necho \"\${FG_V1}\"",
            ParamTemplate.expand("echo \"%{value}\"", variables, ParamExpansion.SHELL_QUOTED),
        )
        assertEquals(
            "FG_V1='a'\"'\"'b'\necho ''\"\${FG_V1}\"''",
            ParamTemplate.expand("echo '%{value}'", variables, ParamExpansion.SHELL_QUOTED),
        )
    }

    @Test
    fun shellExpansionNumbersVariablesInFirstUseOrderAndReusesNames() {
        assertEquals(
            "FG_V1='one'\nFG_V2='two'\necho \"\${FG_V1}\" \"\${FG_V2}\" \"\${FG_V1}\"",
            ParamTemplate.expand(
                "echo %{first} %{second} %{first}",
                mapOf("first" to "one", "second" to "two"),
                ParamExpansion.SHELL_QUOTED,
            ),
        )
    }

    @Test
    fun shellExpansionUsesAnUnquotedReferenceInsideAnUnquotedHeredoc() {
        assertEquals(
            "FG_V1='a; b'\ncat <<EOF\n\${FG_V1}\nEOF",
            ParamTemplate.expand(
                "cat <<EOF\n%{value}\nEOF",
                mapOf("value" to "a; b"),
                ParamExpansion.SHELL_QUOTED,
            ),
        )
    }

    @Test
    fun shellExpansionTreatsCommandSubstitutionAsAFreshCommandContext() {
        assertEquals(
            "FG_V1='a\nb'\necho \"\$(echo \"\${FG_V1}\")\"",
            ParamTemplate.expand(
                "echo \"\$(echo %{value})\"",
                mapOf("value" to "a\nb"),
                ParamExpansion.SHELL_QUOTED,
            ),
        )
        assertEquals(
            "FG_V1='a'\necho \${UNSET_VAR:-\"\${FG_V1}\"}",
            ParamTemplate.expand(
                "echo \${UNSET_VAR:-%{value}}",
                mapOf("value" to "a"),
                ParamExpansion.SHELL_QUOTED,
            ),
        )
    }

    @Test
    fun urlEncodingPercentEncodesReservedCharacters() {
        assertEquals("a%26b%3Fc%23d%2Fe%20f%25", ParamTemplate.expand(
            "%{value}",
            mapOf("value" to "a&b?c#d/e f%"),
            ParamExpansion.URL_ENCODED,
        ))
    }

    @Test
    fun httpRequestEncodesTheUrlSegmentButNotTheBody() {
        val expanded = ParamTemplate.expand(
            "POST https://e.test/p?q=%{value} x&y=%{value}\n第二行",
            mapOf("value" to "a&b?c#d/e f"),
            ParamExpansion.HTTP_REQUEST,
        )

        assertEquals(
            "POST https://e.test/p?q=a%26b%3Fc%23d%2Fe%20f x&y=a&b?c#d/e f\n第二行",
            expanded,
        )
    }

    @Test
    fun unknownVariablesStayLiteralInEveryMode() {
        val variables = mapOf<String, Any?>("other" to "x")
        assertEquals("%{missing}", ParamTemplate.expand("%{missing}", variables, ParamExpansion.SHELL_QUOTED))
        assertEquals("%{missing}", ParamTemplate.expand("%{missing}", variables, ParamExpansion.URL_ENCODED))
        assertEquals(
            "GET https://e.test/%{missing}",
            ParamTemplate.expand("GET https://e.test/%{missing}", variables, ParamExpansion.HTTP_REQUEST),
        )
    }

    @Test
    fun parameterMapUsesEachSpecsDeclaredExpansion() {
        val expanded = ParamTemplate.expand(
            params = mapOf(
                "command" to JsonPrimitive("echo %{value}"),
                "body" to JsonPrimitive("%{value}"),
            ),
            specs = listOf(
                ParamSpec("command", "命令", expansion = ParamExpansion.SHELL_QUOTED),
                ParamSpec("body", "正文"),
            ),
            variables = mapOf("value" to "a; b"),
        )

        assertEquals(
            "FG_V1='a; b'\necho \"\${FG_V1}\"",
            (expanded["command"] as JsonPrimitive).content,
        )
        assertEquals("a; b", (expanded["body"] as JsonPrimitive).content)
    }
}
