package com.flowglass.demo.system.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.flowglass.engine.log.TraceEvent
import com.flowglass.engine.log.TraceJson
import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceLevel
import com.flowglass.engine.log.TraceSink
import com.flowglass.engine.log.TraceStatus
import com.flowglass.engine.log.allows
import com.flowglass.engine.log.newTraceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.util.concurrent.atomic.AtomicBoolean

class AppLogStore(
    context: Context,
    private val settings: LogSettings = LogSettings(context),
    private val cleanupIntervalMs: Long = DEFAULT_CLEANUP_MS,
) : TraceSink {
    private val database = LogDatabase(context)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<TraceEvent>(
        capacity = 2_048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val storageFailureReported = AtomicBoolean(false)
    private var insertedSinceTrim = 0

    init {
        writer.launch {
            runCatching { trimIfNeeded(database.writableDatabase) }
                .onFailure { reportStorageFailure("启动清理", it) }
            for (event in queue) {
                runCatching { insert(event) }
                    .onFailure { reportStorageFailure("写入", it) }
            }
        }
        // Insert-count cleanup covers active logging; this timer also covers a long idle period.
        writer.launch {
            while (true) {
                delay(cleanupIntervalMs)
                runCatching { trimIfNeeded(database.writableDatabase) }
                    .onFailure { reportStorageFailure("定期清理", it) }
            }
        }
    }

    override fun record(event: TraceEvent) {
        if (!settings.accepts(event.level)) return
        if (queue.trySend(event).isFailure) {
            Log.w(TAG, "log queue is full; dropping ${event.kind}")
        }
    }

    /** Writes a CONFIG trace when the user changes a logging setting. */
    fun recordConfig(key: String, oldValue: Any?, newValue: Any?) {
        record(
            TraceEvent(
                traceId = newTraceId(),
                atEpochMs = System.currentTimeMillis(),
                kind = TraceKind.CONFIG,
                status = TraceStatus.INFO,
                level = TraceLevel.INFO,
                source = "LOG_SETTINGS",
                message = "日志设置变更",
                detail = "$key: $oldValue → $newValue",
            ),
        )
    }

    fun query(filter: LogFilter): List<TraceEvent> {
        return runCatching {
            val (where, args) = whereClause(filter)
            val sql = buildString {
                append("SELECT payload FROM trace_events")
                if (where != null) append(" WHERE ").append(where)
                append(" ORDER BY at_ms DESC LIMIT ? OFFSET ?")
            }
            val queryArgs = args + filter.limit.toString() + filter.offset.toString()
            database.readableDatabase.rawQuery(sql, queryArgs.toTypedArray()).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        runCatching {
                            TraceJson.format.decodeFromString(TraceEvent.serializer(), c.getString(0))
                        }.onSuccess { event ->
                            if (event.level.allows(filter.minLevel)) add(event)
                        }
                    }
                }
            }
        }.onFailure { reportStorageFailure("查询", it) }.getOrDefault(emptyList())
    }

    /** Total rows matching the filter, ignoring pagination and the display-level post-filter. */
    fun count(filter: LogFilter): Int {
        return runCatching {
            val (where, args) = whereClause(filter)
            val sql = buildString {
                append("SELECT COUNT(*) FROM trace_events")
                if (where != null) append(" WHERE ").append(where)
            }
            database.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }.onFailure { reportStorageFailure("计数", it) }.getOrDefault(0)
    }

    fun clear() {
        runCatching { database.writableDatabase.delete("trace_events", null, null) }
            .onFailure { reportStorageFailure("清空", it) }
    }

    fun close() {
        writer.cancel()
        database.close()
    }

    private fun whereClause(filter: LogFilter): Pair<String?, List<String>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        clauses += "level <= ?"
        args += filter.minLevel.ordinal.toString()
        if (filter.beforeMs != null) {
            clauses += "at_ms < ?"
            args += filter.beforeMs.toString()
        }
        if (filter.traceId != null) {
            clauses += "trace_id = ?"
            args += filter.traceId
        }
        if (filter.kinds.isNotEmpty()) {
            clauses += "kind IN (${filter.kinds.joinToString(",") { "?" }})"
            args += filter.kinds.map { it.name }
        }
        if (filter.search.isNotBlank()) {
            clauses += "(message LIKE ? ESCAPE '\\' OR payload LIKE ? ESCAPE '\\' " +
                "OR trace_id LIKE ? ESCAPE '\\' OR rule_id LIKE ? ESCAPE '\\' " +
                "OR component_type LIKE ? ESCAPE '\\' OR source LIKE ? ESCAPE '\\')"
            val like = likePattern(filter.search)
            repeat(6) { args += like }
        }
        return (clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")) to args
    }

    private fun likePattern(value: String): String =
        "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun insert(event: TraceEvent) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", event.id)
                put("trace_id", event.traceId)
                put("run_id", event.runId)
                put("at_ms", event.atEpochMs)
                put("level", event.level.ordinal)
                put("kind", event.kind.name)
                put("status", event.status.name)
                put("rule_id", event.ruleId)
                put("source", event.source)
                put("component_type", event.componentTypeId)
                put("message", event.message)
                put("payload", TraceJson.format.encodeToString(TraceEvent.serializer(), event))
            }
            db.insertWithOnConflict("trace_events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (++insertedSinceTrim >= TRIM_EVERY) {
                insertedSinceTrim = 0
                trimIfNeeded(db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun trimIfNeeded(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - settings.retentionDays.toLong() * DAY_MS
        db.delete("trace_events", "at_ms < ?", arrayOf(cutoff.toString()))
        db.execSQL(
            "DELETE FROM trace_events WHERE id NOT IN " +
                "(SELECT id FROM trace_events ORDER BY at_ms DESC LIMIT ${settings.maxRows})",
        )
    }

    /**
     * The sink cannot persist its own write failure. Report once to Logcat and enqueue a STORAGE
     * trace; if SQLite recovers, that diagnostic is persisted by the normal writer.
     */
    private fun reportStorageFailure(stage: String, error: Throwable) {
        Log.e(TAG, "log storage $stage failed", error)
        if (!storageFailureReported.compareAndSet(false, true)) return
        queue.trySend(
            TraceEvent(
                traceId = newTraceId(),
                atEpochMs = System.currentTimeMillis(),
                kind = TraceKind.STORAGE,
                status = TraceStatus.FAILURE,
                level = TraceLevel.ERROR,
                source = "LOG_STORE",
                message = "日志存储失败",
                detail = stage,
                errorCode = error::class.simpleName,
                errorStack = error.stackTraceToString(),
            ),
        )
    }

    private companion object {
        const val TAG = "FlowGlassLog"
        const val TRIM_EVERY = 256
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val DEFAULT_CLEANUP_MS = 6L * 60L * 60L * 1000L
    }
}
