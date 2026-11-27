package com.flowglass.demo.xposed

import android.content.ClipData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardHookPayloadTest {
    @Test
    fun readsClipAndPackageFromArguments() {
        val clip = ClipData.newPlainText("标签", "内容")
        val payload = ClipboardHookPayloadReader.read(
            arrayOf<Any?>(clip, "attribution", "com.android.chrome", 10, 0),
        )
        assertNotNull(payload)
        assertEquals("内容", payload!!.text)
        assertEquals("com.android.chrome", payload.packageName)
        assertEquals(10, payload.userId)
        assertEquals("text/plain", payload.mimeType)
    }

    @Test
    fun ignoresEmptyClipAndMissingPackage() {
        assertNull(ClipboardHookPayloadReader.read(arrayOf<Any?>(ClipData.newPlainText("空", ""))))
        assertNull(ClipboardHookPayloadReader.read(emptyArray()))
    }

    @Test
    fun truncatesVeryLargeClipboardText() {
        val payload = ClipboardHookPayloadReader.read(
            arrayOf<Any?>(ClipData.newPlainText("长", "x".repeat(40_000)), "com.a", 0),
        )
        assertNotNull(payload)
        assertEquals(ClipboardHookPayloadReader.MAX_TEXT_CHARS, payload!!.text.length)
    }

    @Test
    fun clipboardCandidateIsDeclaredForTheSystemFamily() {
        val candidate = SystemHookCandidates.clipboard.single()
        assertEquals(SystemHookFamily.CLIPBOARD, candidate.family)
        assertEquals(SystemHookProtocol.EVENT_CLIPBOARD_CHANGED, candidate.kind)
        assertEquals("com.android.server.clipboard.ClipboardService", candidate.className)
    }
}
