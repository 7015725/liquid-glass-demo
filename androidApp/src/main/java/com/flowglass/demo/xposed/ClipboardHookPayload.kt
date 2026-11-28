package com.flowglass.demo.xposed

import android.content.ClipData

internal data class ClipboardHookPayload(
    val text: String,
    val packageName: String,
    val userId: Int,
    val mimeType: String,
    val label: String,
)

/**
 * Reads only plain values from the hooked method's arguments. The system hook must never retain
 * a ClipData object after the callback returns.
 */
internal object ClipboardHookPayloadReader {
    const val MAX_TEXT_CHARS = 32_768

    fun read(arguments: Array<Any?>): ClipboardHookPayload? {
        val clip = arguments.firstOrNull { it is ClipData } as? ClipData ?: return null
        val item = clip.getItemAt(0) ?: return null
        val raw = when {
            item.text != null -> item.text.toString()
            item.uri != null -> item.uri.toString()
            else -> return null
        }
        if (raw.isBlank()) return null

        // Calling package is always the first valid package argument: Android 14/15 also pass
        // an attribution tag after it, which must not win.
        val packageName = arguments
            .filterIsInstance<String>()
            .firstOrNull { HookIdentityReader.isValidPackage(it) }
            .orEmpty()
        val userId = arguments
            .filterIsInstance<Int>()
            .firstOrNull { it in 0..999_999 }
            ?: 0
        return ClipboardHookPayload(
            text = raw.take(MAX_TEXT_CHARS),
            packageName = packageName,
            userId = userId,
            mimeType = clip.description?.getMimeType(0)?.takeIf { it.isNotBlank() } ?: "text/plain",
            label = clip.description?.label?.toString().orEmpty().take(200),
        )
    }
}
