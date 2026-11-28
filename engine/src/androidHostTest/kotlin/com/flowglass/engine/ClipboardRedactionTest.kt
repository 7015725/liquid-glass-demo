package com.flowglass.engine

import com.flowglass.engine.log.TraceRedaction
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardRedactionTest {
    @Test
    fun clipboardBodyIsAlwaysRedactedButPackageStaysReadable() {
        val output = TraceRedaction.event(
            mapOf(
                "clipboard.text" to "my-password-123",
                "clipboard.package" to "com.android.chrome",
            ),
            includeRaw = true,
        )
        assertEquals("***", output["clipboard.text"])
        assertEquals("com.android.chrome", output["clipboard.package"])
    }

    @Test
    fun clipboardVariableNameIsRedacted() {
        val output = TraceRedaction.values(mapOf("剪贴板内容" to "secret"))
        assertEquals("***", output["剪贴板内容"])
    }
}
