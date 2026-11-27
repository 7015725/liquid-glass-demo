package com.flowglass.demo.system.log

import android.content.Context
import com.flowglass.engine.log.TraceLevel

class LogSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var level: TraceLevel
        get() = runCatching { TraceLevel.valueOf(prefs.getString(KEY_LEVEL, TraceLevel.INFO.name)!!) }
            .getOrDefault(TraceLevel.INFO)
        set(value) = prefs.edit().putString(KEY_LEVEL, value.name).apply()

    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS).coerceIn(1, 365)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value.coerceIn(1, 365)).apply()

    var maxRows: Int
        get() = prefs.getInt(KEY_MAX_ROWS, DEFAULT_MAX_ROWS).coerceIn(1_000, 1_000_000)
        set(value) = prefs.edit().putInt(KEY_MAX_ROWS, value.coerceIn(1_000, 1_000_000)).apply()

    var includeRawEvents: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_RAW, true)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_RAW, value).apply()

    var writeLogcat: Boolean
        get() = prefs.getBoolean(KEY_LOGCAT, true)
        set(value) = prefs.edit().putBoolean(KEY_LOGCAT, value).apply()

    fun accepts(eventLevel: TraceLevel): Boolean = enabled && eventLevel.ordinal <= level.ordinal

    private companion object {
        const val PREFS = "flowglass_logs"
        const val KEY_ENABLED = "enabled"
        const val KEY_LEVEL = "level"
        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_MAX_ROWS = "max_rows"
        const val KEY_INCLUDE_RAW = "include_raw_events"
        const val KEY_LOGCAT = "write_logcat"
        const val DEFAULT_RETENTION_DAYS = 7
        const val DEFAULT_MAX_ROWS = 50_000
    }
}
