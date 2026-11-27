package com.flowglass.demo.system.clipboard

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardArchiveTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun recordsSystemPayloadAndBuildsDatabaseEntry() {
        val store = ClipboardHistoryStore(context, "archive-${System.nanoTime()}.db")
        val payload = ClipboardPayload(
            content = "全局内容",
            originalLength = 4,
            packageName = "com.android.chrome",
            userId = 10,
            mimeType = "text/plain",
            label = "标签",
            source = ClipboardCaptureSource.SYSTEM_HOOK,
            atEpochMs = 1_700_000_000_000,
            truncated = false,
        )

        assertTrue(ClipboardArchive.recordForTest(payload, store))
        val row = store.query(ClipboardQuery(limit = 1)).rows.single()
        assertEquals("全局内容", row.content)
        assertEquals("com.android.chrome", row.packageName)
        assertEquals(10, row.userId)
        assertEquals(ClipboardCaptureSource.SYSTEM_HOOK, row.source)
        store.close()
    }

    @Test
    fun suppressedOwnCopyIsNotRecordedAgain() {
        val store = ClipboardHistoryStore(context, "suppress-${System.nanoTime()}.db")
        val payload = ClipboardPayload(
            content = "来自浮窗的复制", originalLength = 7,
            packageName = "com.flowglass.demo", userId = 0,
            mimeType = "text/plain", label = "",
            source = ClipboardCaptureSource.FOREGROUND,
            atEpochMs = 1_700_000_000_000, truncated = false,
        )
        ClipboardArchive.suppressNextForTest("来自浮窗的复制")
        assertFalse(ClipboardArchive.recordForTest(payload, store))
        assertTrue(store.query(ClipboardQuery(limit = 10)).rows.isEmpty())
        store.close()
    }
}
