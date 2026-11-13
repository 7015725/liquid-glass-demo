package com.flowglass.demo.system.exec

import android.os.Looper
import com.flowglass.demo.ui.AppState
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpTaskCancellationTest {
    private class BlockedConnection(url: URL) : HttpURLConnection(url) {
        val readStarted = CompletableDeferred<Unit>()
        val readFinished = CompletableDeferred<Unit>()
        val disconnectCalls = AtomicInteger()
        private val releaseRead = CountDownLatch(1)
        private val body = object : InputStream() {
            override fun read(): Int {
                readStarted.complete(Unit)
                while (true) {
                    try {
                        releaseRead.await()
                        readFinished.complete(Unit)
                        return -1
                    } catch (_: InterruptedException) {
                        // Model a platform read that does not cooperate with coroutine cancellation.
                    }
                }
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                readStarted.complete(Unit)
                while (true) {
                    try {
                        releaseRead.await()
                        readFinished.complete(Unit)
                        return -1
                    } catch (_: InterruptedException) {
                        // Model a platform read that does not cooperate with coroutine cancellation.
                    }
                }
            }
        }

        fun unblock() = releaseRead.countDown()
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = body
        override fun disconnect() { disconnectCalls.incrementAndGet() }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    @Test fun cancellingInstructionReturnsWithoutOutcomeOrLogAndDisconnects() = runBlocking {
        val connection = BlockedConnection(URL("http://example.test/cancel"))
        val outcomes = ConcurrentLinkedQueue<TaskOutcome>()
        val engine = ExecutionEngine(RuntimeEnvironment.getApplication()) { _, _ ->
            HttpTask.run(
                TaskParameters.http("GET http://example.test/cancel"),
                connectionFactory = { connection },
            ).also(outcomes::add)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = AppState().apply { bindExecutionEngine(engine) }
        val command = Cmd(
            id = 99_001,
            auto = false,
            ic = Ic.Bolt,
            title = "HTTP 取消",
            desc = "",
            actions = listOf(CommandItem(99_002, "HTTP 请求", "GET http://example.test/cancel", Ic.Bolt)),
        )
        state.oneTaps.add(command)
        val job = requireNotNull(state.execute(command, scope, "取消测试"))
        try {
            withTimeout(2_000) { connection.readStarted.await() }
            val started = System.nanoTime()
            job.cancel()
            withTimeout(200) {
                while (!job.isCompleted) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(1)
                }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue("cancelled caller should return promptly: ${elapsedMs}ms", elapsedMs < 200)
            assertTrue("cancelled request must disconnect the active connection", connection.disconnectCalls.get() >= 1)
            assertTrue("cancelled request must not return an outcome", outcomes.isEmpty())
            assertTrue("cancelled instruction must not write execution logs", state.logs.isEmpty())
            assertTrue("cancelled instruction must not increment run count", state.todayRuns == 0)
        } finally {
            connection.unblock()
            withTimeout(2_000) { connection.readFinished.await() }
            delay(50)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue("worker completion after cancellation must not return an outcome", outcomes.isEmpty())
            assertTrue("worker completion after cancellation must not write logs", state.logs.isEmpty())
            assertTrue("worker completion after cancellation must not increment run count", state.todayRuns == 0)
            scope.cancel()
        }
    }
}
