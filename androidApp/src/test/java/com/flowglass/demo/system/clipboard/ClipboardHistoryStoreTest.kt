package com.flowglass.demo.system.clipboard

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardHistoryStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store(name: String): ClipboardHistoryStore =
        ClipboardHistoryStore(context, "test-$name-${System.nanoTime()}.db")

    private fun entry(
        content: String = "hello",
        at: Long = 1_700_000_000_000,
        packageName: String = "com.android.chrome",
    ) = ClipboardEntry(
        changedAt = at,
        changedAtText = "2023-11-14 06:13:20",
        packageName = packageName,
        userId = 0,
        mimeType = "text/plain",
        label = "",
        content = content,
        contentLength = content.length,
        source = ClipboardCaptureSource.SYSTEM_HOOK,
        truncated = false,
    )

    @Test
    fun recordsAllRequiredColumnsAndReadsNewestFirst() {
        val store = store("columns")
        val first = store.record(entry())
        val second = store.record(entry(content = "world", at = 1_700_000_001_000))
        assertNotNull(first)
        assertNotNull(second)

        val page = store.query(ClipboardQuery(limit = 10))
        assertEquals(2, page.total)
        assertEquals(listOf("world", "hello"), page.rows.map { it.content })
        val row = page.rows.last()
        assertEquals(1_700_000_000_000, row.changedAt)
        assertEquals("2023-11-14 06:13:20", row.changedAtText)
        assertEquals("com.android.chrome", row.packageName)
        assertEquals(0, row.userId)
        assertEquals("text/plain", row.mimeType)
        assertEquals(5, row.contentLength)
        store.close()
    }

    @Test
    fun searchesContentAndPackageAndPaginates() {
        val store = store("search")
        store.record(entry(content = "alpha 50% done", packageName = "com.a"))
        store.record(entry(content = "beta event", packageName = "com.b", at = 1_700_000_001_000))
        store.record(entry(content = "alpha again", packageName = "com.c", at = 1_700_000_002_000))

        val byContent = store.query(ClipboardQuery(search = "alpha", limit = 10))
        assertEquals(2, byContent.total)
        assertEquals(2, byContent.rows.size)

        val byPackage = store.query(ClipboardQuery(search = "com.b", limit = 10))
        assertEquals(1, byPackage.total)
        assertEquals("beta event", byPackage.rows.single().content)

        val page = store.query(ClipboardQuery(search = "alpha", limit = 1, offset = 1))
        assertEquals(2, page.total)
        assertEquals(1, page.rows.size)
        store.close()
    }

    @Test
    fun deduplicatesTheSameHashInsideTheWindowButKeepsRepeatedCopiesAfterIt() {
        val store = store("dedupe")
        assertNotNull(store.record(entry(content = "same", at = 1_000)))
        assertNull(store.record(entry(content = "same", at = 1_500)))
        assertNotNull(store.record(entry(content = "same", at = 3_000)))
        assertEquals(2, store.query(ClipboardQuery(limit = 10)).total)
        store.close()
    }

    @Test
    fun trimsByRetentionAndMaximumRows() {
        val store = store("trim")
        repeat(5) { index ->
            store.record(entry(content = "v$index", at = 10_000L + index))
        }
        store.trim(now = 20_000L, retentionDays = 1, maxEntries = 3)
        val page = store.query(ClipboardQuery(limit = 10))
        assertEquals(3, page.total)
        assertEquals(listOf("v4", "v3", "v2"), page.rows.map { it.content })
        store.close()
    }
}
