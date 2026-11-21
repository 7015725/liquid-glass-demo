package com.flowglass.demo.system.exec

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * 重定向降级：HTTPS 请求被 302 指向 http:// 时不得自动跟随，否则明文段会绕过用户的显式配置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RedirectDowngradeTest {
    private class Reply(
        url: URL,
        private val status: Int,
        private val location: String?,
        private val body: String = "",
    ) : HttpURLConnection(url) {
        var disconnected = false
            private set

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? =
            if (name.equals("Location", ignoreCase = true)) location else null
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream? = null
    }

    private class Factory(private val replies: List<Reply>) {
        val requested = mutableListOf<String>()
        fun create(url: URL): HttpURLConnection {
            requested += url.toString()
            check(requested.size <= replies.size) { "unexpected request to $url" }
            return replies[requested.size - 1]
        }
    }

    private fun run(spec: String, replies: List<Reply>): Pair<TaskOutcome, Factory> {
        val factory = Factory(replies)
        val parsed = spec.split(' ')
        val outcome = runBlocking {
            HttpTask.run(
                HttpRequestSpec(parsed[0], URL(parsed[1]), parsed.getOrNull(2)),
                timeoutMs = 5_000,
                connectionFactory = factory::create,
            )
        }
        return outcome to factory
    }

    @Test
    fun httpsRedirectToHttpIsRefused() {
        val (outcome, factory) = run(
            "GET https://example.test/start",
            listOf(
                Reply(URL("https://example.test/start"), 302, "http://example.test/plain"),
                // Present so the unfixed path can complete: it would happily return the plaintext body.
                Reply(URL("http://example.test/plain"), 200, null, "secret-in-clear"),
            ),
        )

        assertEquals(StepStatus.FAILURE, outcome.status)
        assertTrue("必须说明拒绝降级：${outcome.detail}", outcome.detail.contains("降级"))
        assertEquals("不得请求明文地址", 1, factory.requested.size)
    }

    @Test
    fun plainHttpRedirectToHttpIsStillFollowed() {
        val (outcome, factory) = run(
            "GET http://example.test/start",
            listOf(
                Reply(URL("http://example.test/start"), 302, "http://example.test/next"),
                Reply(URL("http://example.test/next"), 200, null, "ok"),
            ),
        )

        assertEquals(StepStatus.SUCCESS, outcome.status)
        assertEquals(listOf("http://example.test/start", "http://example.test/next"), factory.requested)
        assertTrue(outcome.detail.contains("ok"))
    }

    @Test
    fun httpsRedirectToHttpsIsStillFollowed() {
        val (outcome, _) = run(
            "GET https://example.test/start",
            listOf(
                Reply(URL("https://example.test/start"), 307, "https://example.test/next"),
                Reply(URL("https://example.test/next"), 200, null, "secret"),
            ),
        )

        assertEquals(StepStatus.SUCCESS, outcome.status)
        assertTrue(outcome.detail.contains("secret"))
    }
}
