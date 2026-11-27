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

    @Test
    fun searchesPaginatesAndFiltersByTraceId() = runBlocking {
        val settings = LogSettings(context).apply {
            enabled = true
            level = TraceLevel.TRACE
        }
        val store = AppLogStore(context, settings)
        store.clear()
        val now = System.currentTimeMillis()
        store.record(
            TraceEvent(
                traceId = "trace-a", atEpochMs = now - 2_000, kind = TraceKind.ACTION,
                status = TraceStatus.SUCCESS, level = TraceLevel.INFO,
                message = "alpha 50% done", ruleId = "rule-a",
            ),
        )
        store.record(
            TraceEvent(
                traceId = "trace-b", atEpochMs = now - 1_000, kind = TraceKind.ACTION,
                status = TraceStatus.SUCCESS, level = TraceLevel.INFO,
                message = "beta event", ruleId = "rule-b",
            ),
        )
        store.record(
            TraceEvent(
                traceId = "trace-c", atEpochMs = now, kind = TraceKind.SYSTEM,
                status = TraceStatus.INFO, level = TraceLevel.INFO,
                message = "gamma system", source = "TRIGGER_SERVICE",
            ),
        )
        waitForRows(store, LogFilter(limit = 10), 3)

        val firstPage = store.query(LogFilter(limit = 1))
        val secondPage = store.query(LogFilter(limit = 1, offset = 1))
        assertEquals(3, store.count(LogFilter(limit = 1)))
        assertEquals("gamma system", firstPage.single().message)
        assertEquals("beta event", secondPage.single().message)
        assertEquals(1, store.query(LogFilter(search = "beta", limit = 10)).size)
        assertEquals(1, store.query(LogFilter(search = "50%", limit = 10)).size)
        assertEquals(1, store.query(LogFilter(traceId = "trace-a", limit = 10)).size)
        store.clear()
        store.close()
    }

    @Test
    fun periodicCleanupRemovesRowsOlderThanRetention() = runBlocking {
        val settings = LogSettings(context).apply {
            enabled = true
            level = TraceLevel.TRACE
            retentionDays = 7
        }
        val store = AppLogStore(context, settings, cleanupIntervalMs = 100)
        store.clear()
        store.record(
            TraceEvent(
                traceId = "trace-old",
                atEpochMs = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000,
                kind = TraceKind.ACTION,
                status = TraceStatus.SUCCESS,
                level = TraceLevel.INFO,
                message = "expired",
            ),
        )
        waitForRows(store, LogFilter(limit = 10), 1)
        val removed = withTimeoutOrNull(3_000) {
            while (true) {
                if (store.count(LogFilter(limit = 10)) == 0) return@withTimeoutOrNull true
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        assertEquals(true, removed)
        store.close()
    }

    private suspend fun waitForRows(store: AppLogStore, filter: LogFilter, expected: Int) {
        withTimeoutOrNull(2_000) {
            while (store.count(filter) != expected) delay(20)
        }
        assertEquals(expected, store.count(filter))
    }
}
