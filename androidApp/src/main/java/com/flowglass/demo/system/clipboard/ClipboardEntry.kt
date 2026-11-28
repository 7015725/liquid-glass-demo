package com.flowglass.demo.system.clipboard

internal enum class ClipboardCaptureSource { SYSTEM_HOOK, FOREGROUND, UNKNOWN }

internal data class ClipboardEntry(
    val id: Long = 0L,
    val changedAt: Long,
    val changedAtText: String,
    val packageName: String,
    val userId: Int,
    val mimeType: String,
    val label: String,
    val content: String,
    val contentLength: Int,
    val source: ClipboardCaptureSource,
    val truncated: Boolean,
)

internal data class ClipboardQuery(
    val search: String = "",
    val limit: Int = 200,
    val offset: Int = 0,
)

internal data class ClipboardPage(
    val rows: List<ClipboardEntry>,
    val total: Int,
)
