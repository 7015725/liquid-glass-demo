package com.flowglass.demo.system.log

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.flowglass.engine.log.TraceEvent
import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceLevel
import com.flowglass.engine.log.TraceStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLogStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun recordsQueriesAndClearsTraceEvents() = runBlocking {
        val settings = LogSettings(context).apply {
            enabled = true
            level = TraceLevel.TRACE
        }
        val store = AppLogStore(context, settings)
        store.clear()
        val event = TraceEvent(
            traceId = "trace-test",
            atEpochMs = System.currentTimeMillis(),
            kind = TraceKind.ACTION,
            status = TraceStatus.SUCCESS,
            level = TraceLevel.INFO,
            message = "测试动作",
            ruleId = "rule-1",
        )

        store.record(event)
        val found = withTimeoutOrNull(2_000) {
            while (true) {
                val rows = store.query(LogFilter(limit = 10))
                if (rows.isNotEmpty()) return@withTimeoutOrNull rows
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList<TraceEvent>()
        }

        assertEquals(1, found?.size ?: 0)
        assertEquals("测试动作", found?.firstOrNull()?.message)
        store.clear()
        assertEquals(0, store.query(LogFilter(limit = 10)).size)
        store.close()
    }
}
