package com.flowglass.demo.system.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.flowglass.engine.log.TraceEvent
import com.flowglass.engine.log.TraceJson
import com.flowglass.engine.log.TraceLevel
import com.flowglass.engine.log.TraceSink
import com.flowglass.engine.log.allows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

class AppLogStore(
    context: Context,
    private val settings: LogSettings = LogSettings(context),
) : TraceSink {
    private val database = LogDatabase(context)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<TraceEvent>(
        capacity = 2_048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var insertedSinceTrim = 0

    init {
        writer.launch {
            runCatching { trimIfNeeded(database.writableDatabase) }
                .onFailure { Log.w(TAG, "initial log trim failed", it) }
            for (event in queue) {
                runCatching { insert(event) }
                    .onFailure { Log.w(TAG, "log insert failed", it) }
            }
        }
    }

    override fun record(event: TraceEvent) {
        if (!settings.accepts(event.level)) return
        if (queue.trySend(event).isFailure) {
            Log.w(TAG, "log queue is full; dropping ${event.kind}")
        }
    }

    fun query(filter: LogFilter): List<TraceEvent> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        clauses += "level <= ?"
        args += filter.minLevel.ordinal.toString()
        if (filter.beforeMs != null) {
            clauses += "at_ms < ?"
            args += filter.beforeMs.toString()
        }
        if (filter.kinds.isNotEmpty()) {
            clauses += "kind IN (${filter.kinds.joinToString(",") { "?" }})"
            args += filter.kinds.map { it.name }
        }
        if (filter.search.isNotBlank()) {
            clauses += "(message LIKE ? OR trace_id LIKE ? OR rule_id LIKE ? OR component_type LIKE ?)"
            val like = "%${filter.search}%"
            args += listOf(like, like, like, like)
        }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val cursor = database.readableDatabase.query(
            "trace_events",
            arrayOf("payload"),
            where,
            args.toTypedArray(),
            null,
            null,
            "at_ms DESC",
            filter.limit.toString(),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    runCatching {
                        TraceJson.format.decodeFromString(TraceEvent.serializer(), c.getString(0))
                    }.onSuccess { event -> add(event) }
                }
            }
        }.filter { it.level.allows(filter.minLevel) }
    }

    fun clear() {
        database.writableDatabase.delete("trace_events", null, null)
    }

    fun close() {
        writer.cancel()
        database.close()
    }

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

    private companion object {
        const val TAG = "FlowGlassLog"
        const val TRIM_EVERY = 256
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
