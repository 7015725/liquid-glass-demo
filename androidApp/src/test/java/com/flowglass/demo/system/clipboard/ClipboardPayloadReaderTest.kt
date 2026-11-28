package com.flowglass.demo.system.clipboard

import android.content.ClipData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardPayloadReaderTest {
    @Test
    fun readsTextPackageMimeAndTimestamp() {
        val payload = ClipboardPayloadReader.fromClip(
            clip = ClipData.newPlainText("标签", "第一行\n第二行"),
            packageName = "com.android.chrome",
            userId = 10,
            source = ClipboardCaptureSource.SYSTEM_HOOK,
            atEpochMs = 1_700_000_000_000,
        )
        assertNotNull(payload)
        assertEquals("第一行\n第二行", payload!!.content)
        assertEquals("com.android.chrome", payload.packageName)
        assertEquals(10, payload.userId)
        assertEquals("text/plain", payload.mimeType)
        assertEquals(ClipboardCaptureSource.SYSTEM_HOOK, payload.source)
    }

    @Test
    fun ignoresEmptyClips() {
        val payload = ClipboardPayloadReader.fromClip(
            clip = ClipData.newPlainText("空", ""),
            packageName = "com.a",
            userId = 0,
            source = ClipboardCaptureSource.FOREGROUND,
            atEpochMs = 1L,
        )
        assertNull(payload)
    }

    @Test
    fun truncatesAtTheConfiguredLimit() {
        val payload = ClipboardPayloadReader.fromClip(
            clip = ClipData.newPlainText("长文本", "x".repeat(40_000)),
            packageName = "",
            userId = 0,
            source = ClipboardCaptureSource.FOREGROUND,
            atEpochMs = 1L,
        )
        assertNotNull(payload)
        assertEquals(ClipboardPayloadReader.MAX_CONTENT_CHARS, payload!!.content.length)
        assertTrue(payload.truncated)
        assertEquals(40_000, payload.originalLength)
    }
}
