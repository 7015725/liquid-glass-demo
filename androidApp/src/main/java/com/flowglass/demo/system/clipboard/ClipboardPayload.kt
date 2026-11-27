package com.flowglass.demo.system.clipboard

import android.content.ClipData

internal data class ClipboardPayload(
    val content: String,
    val originalLength: Int,
    val packageName: String,
    val userId: Int,
    val mimeType: String,
    val label: String,
    val source: ClipboardCaptureSource,
    val atEpochMs: Long,
    val truncated: Boolean,
)

internal object ClipboardPayloadReader {
    const val MAX_CONTENT_CHARS = 32_768

    fun fromClip(
        clip: ClipData?,
        packageName: String,
        userId: Int,
        source: ClipboardCaptureSource,
        atEpochMs: Long,
    ): ClipboardPayload? {
        val item = clip?.getItemAt(0) ?: return null
        val raw = when {
            item.text != null -> item.text.toString()
            item.uri != null -> item.uri.toString()
            else -> return null
        }
        if (raw.isBlank()) return null
        val content = raw.take(MAX_CONTENT_CHARS)
        return ClipboardPayload(
            content = content,
            originalLength = raw.length,
            packageName = packageName.trim().take(255),
            userId = userId.coerceIn(0, 999_999),
            mimeType = clip.description?.getMimeType(0)?.takeIf { it.isNotBlank() } ?: "text/plain",
            label = clip.description?.label?.toString().orEmpty().take(200),
            source = source,
            atEpochMs = atEpochMs,
            truncated = raw.length > MAX_CONTENT_CHARS,
        )
    }
}
