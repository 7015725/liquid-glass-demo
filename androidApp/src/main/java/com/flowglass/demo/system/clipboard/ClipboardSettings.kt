package com.flowglass.demo.system.clipboard

import android.content.Context

internal class ClipboardSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value.coerceIn(1, 90)).apply()

    var maxEntries: Int
        get() = prefs.getInt(KEY_MAX_ENTRIES, DEFAULT_MAX_ENTRIES)
        set(value) = prefs.edit().putInt(KEY_MAX_ENTRIES, value.coerceIn(100, 5_000)).apply()

    companion object {
        const val PREFS = "flowglass_clipboard"
        const val DEFAULT_RETENTION_DAYS = 7
        const val DEFAULT_MAX_ENTRIES = 5_000
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_MAX_ENTRIES = "max_entries"

        fun isEnabled(context: Context): Boolean = ClipboardSettings(context).enabled
    }
}
