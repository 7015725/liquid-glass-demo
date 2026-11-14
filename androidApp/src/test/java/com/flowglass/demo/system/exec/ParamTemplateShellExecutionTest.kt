package com.flowglass.demo.system.exec

import com.flowglass.engine.ParamExpansion
import com.flowglass.engine.ParamSpec
import com.flowglass.engine.ParamTemplate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Executes the expanded command on the real /bin/sh; safety is proven, not simulated. */
class ParamTemplateShellExecutionTest {
    @Test
    fun unquotedTokenIsOneLiteralArgument() = assertSafe("printf '%s' %{payload}") { it }

    @Test
    fun doubleQuotedTokenIsOneLiteralArgument() = assertSafe("printf '%s' \"%{payload}\"") { it }

    @Test
    fun singleQuotedTokenIsOneLiteralArgument() = assertSafe("printf '%s' '%{payload}'") { it }

    @Test
    fun commandSubstitutionInsideDoubleQuotesDoesNotExecute() =
        assertSafe("echo \"\$(echo %{payload})\"") { "$it\n" }

    @Test
    fun backtickCommandSubstitutionInsideDoubleQuotesDoesNotExecute() =
        assertSafe("echo \"`echo %{payload}`\"") { "$it\n" }

    @Test
    fun secondEchoWordDoesNotExecute() = assertSafe("echo echo %{payload}") { "echo $it\n" }

    @Test
    fun unquotedHeredocDoesNotExecute() = assertSafe("cat <<EOF\n%{payload}\nEOF") { "$it\n" }

    @Test
    fun parameterExpansionWordDoesNotExecute() =
        assertSafe("echo \${UNSET_VAR:-%{payload}}") { "$it\n" }

    private fun assertSafe(template: String, expected: (String) -> String) = runBlocking {
        val dir = Files.createTempDirectory("flowglass-shell-")
        try {
            val payload = payload(dir.resolve("pwned"))
            val expanded = ParamTemplate.expand(
                params = mapOf("command" to JsonPrimitive(template)),
                specs = listOf(ParamSpec("command", "命令", expansion = ParamExpansion.SHELL_QUOTED)),
                variables = mapOf("payload" to payload),
            )
            val command = (expanded["command"] as JsonPrimitive).content
            val result = BoundedProcess.run(listOf("/bin/sh", "-c", command), timeoutMs = 10_000)

            assertEquals("template=$template exit\n$command", 0, result.exitCode)
            assertEquals("template=$template stdout\n$command", expected(payload), result.output)
            assertFalse("template=$template ran ; command", Files.exists(dir.resolve("pwned")))
            assertFalse("template=$template ran \$(...) command", Files.exists(dir.resolve("pwned.sub")))
            assertFalse("template=$template ran backtick command", Files.exists(dir.resolve("pwned.tick")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun payload(sentinel: Path) =
        "safe; touch $sentinel; echo && echo || echo | cat\n" +
            "quote'\" back\\slash parens() \$(touch ${sentinel}.sub) `touch ${sentinel}.tick`"
}
