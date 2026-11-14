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

/** Executes the expanded command on the real /bin/sh; quoting is proven, not simulated. */
class ParamTemplateShellExecutionTest {
    @Test
    fun shellQuotedValueIsOneLiteralArgumentInEveryQuoteContext() = runBlocking {
        // The token is unquoted, inside double quotes, and inside single quotes. A naive
        // single-quote wrapper leaves the first two safe but can break out of double quotes.
        val templates = listOf(
            "printf '%s' %{payload}",
            "printf '%s' \"%{payload}\"",
            "printf '%s' '%{payload}'",
        )

        for (template in templates) {
            val dir = Files.createTempDirectory("flowglass-shell-")
            val payload = payload(dir.resolve("pwned"))

            val expanded = ParamTemplate.expand(
                params = mapOf("command" to JsonPrimitive(template)),
                specs = listOf(ParamSpec("command", "命令", expansion = ParamExpansion.SHELL_QUOTED)),
                variables = mapOf("payload" to payload),
            )
            val command = (expanded["command"] as JsonPrimitive).content
            val result = BoundedProcess.run(listOf("/bin/sh", "-c", command), timeoutMs = 10_000)

            assertEquals("template=$template exit", 0, result.exitCode)
            assertEquals("template=$template stdout", payload, result.output)
            assertFalse("template=$template ran ; command", Files.exists(dir.resolve("pwned")))
            assertFalse("template=$template ran \$(...) command", Files.exists(dir.resolve("pwned.sub")))
            assertFalse("template=$template ran backtick command", Files.exists(dir.resolve("pwned.tick")))
        }
    }

    private fun payload(sentinel: Path) =
        "safe; touch $sentinel; echo && echo || echo | cat\n" +
            "quote'\" parens() \$(touch ${sentinel}.sub) `touch ${sentinel}.tick`"
}
