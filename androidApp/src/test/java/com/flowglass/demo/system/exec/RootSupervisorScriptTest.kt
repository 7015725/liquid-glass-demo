package com.flowglass.demo.system.exec

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSupervisorScriptTest {
    @Test
    fun separatesStdoutAndStderrAndKeepsTheExitCode() {
        val id = "supervisor-${UUID.randomUUID()}"
        val command = "printf 'out\\n'; printf 'err\\n' >&2; exit 7"
        val payload = Base64.getEncoder().encodeToString(command.toByteArray(StandardCharsets.UTF_8))
        val script = suSupervisorScript("/bin/sh", "/tmp")

        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val writer = process.outputStream.bufferedWriter()
        val reader = process.inputStream.bufferedReader()
        writer.write("$id:$payload\n")
        writer.flush()
        val output = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            output.append(line).append('\n')
            if (line.startsWith("__FLOWGLASS_EXIT__:$id:")) break
        }
        writer.close()
        assertTrue("supervisor did not exit", process.waitFor(10, TimeUnit.SECONDS))

        val lines = output.lineSequence().toList()
        assertTrue(output.toString(), lines.contains("out"))
        assertTrue(output.toString(), lines.contains("__FLOWGLASS_ERR__:$id:err"))
        assertFalse(output.toString(), lines.contains("err"))
        assertTrue(output.toString(), lines.contains("__FLOWGLASS_EXIT__:$id:7"))
    }
}
