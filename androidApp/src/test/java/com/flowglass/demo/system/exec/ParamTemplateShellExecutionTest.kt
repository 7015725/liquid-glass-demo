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

/** Executes the expanded command on the real /bin/sh; quoting is proven, not simulated. */
class ParamTemplateShellExecutionTest {
    @Test
    fun shellQuotedValueIsOneLiteralArgumentAndCannotRunCommands() = runBlocking {
        val dir = Files.createTempDirectory("flowglass-shell-")
        val sentinel = dir.resolve("pwned")
        val payload = "safe; touch $sentinel; echo && echo || echo | cat\n" +
            "quote'\" parens() \$(touch ${sentinel}.sub) `touch ${sentinel}.tick`"

        val expanded = ParamTemplate.expand(
            params = mapOf("command" to JsonPrimitive("printf '%s' %{payload}")),
            specs = listOf(ParamSpec("command", "命令", expansion = ParamExpansion.SHELL_QUOTED)),
            variables = mapOf("payload" to payload),
        )
        val command = (expanded["command"] as JsonPrimitive).content

        val result = BoundedProcess.run(listOf("/bin/sh", "-c", command), timeoutMs = 10_000)

        assertEquals(0, result.exitCode)
        assertEquals(payload, result.output)
        assertFalse("injected ; command ran", Files.exists(sentinel))
        assertFalse("injected \$(...) command ran", Files.exists(dir.resolve("pwned.sub")))
        assertFalse("injected backtick command ran", Files.exists(dir.resolve("pwned.tick")))
    }
}
