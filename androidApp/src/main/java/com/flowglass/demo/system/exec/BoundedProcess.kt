package com.flowglass.demo.system.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

internal data class ProcessResult(val exitCode: Int, val output: String, val timedOut: Boolean)
internal object BoundedProcess {
    suspend fun run(arguments: List<String>, timeoutMs: Long = 30_000, outputLimit: Int = 500): ProcessResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val bytes = ByteArrayOutputStream(outputLimit * 4 + 4)
        val buffer = ByteArray(4096)
        try {
            process.outputStream.close()
            val input = process.inputStream
            var timedOut = false
            while (true) {
                if (System.nanoTime() - started >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                    timedOut = true
                    break
                }
                currentCoroutineContext().ensureActive()
                val available = input.available()
                if (available > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, available))
                    if (count > 0) {
                        val retain = minOf(count, outputLimit * 4 + 4 - bytes.size())
                        if (retain > 0) bytes.write(buffer, 0, retain)
                    }
                } else if (!process.isAlive) break
                else delay(10)
            }
            ProcessResult(if (timedOut) -1 else process.exitValue(), bytes.toString(StandardCharsets.UTF_8.name()).take(outputLimit), timedOut)
        } finally {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            for (stream in listOf(process.inputStream, process.errorStream, process.outputStream)) {
                try { stream.close() } catch (_: IOException) { }
            }
        }
    }
}
