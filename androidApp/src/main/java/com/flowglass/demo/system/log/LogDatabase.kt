package com.flowglass.demo.system.log

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class LogDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    NAME,
    null,
    VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trace_events (
                id TEXT PRIMARY KEY NOT NULL,
                trace_id TEXT NOT NULL,
                run_id TEXT,
                at_ms INTEGER NOT NULL,
                level INTEGER NOT NULL,
                kind TEXT NOT NULL,
                status TEXT NOT NULL,
                rule_id TEXT,
                source TEXT,
                component_type TEXT,
                message TEXT NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_trace_time ON trace_events(at_ms DESC)")
        db.execSQL("CREATE INDEX idx_trace_id ON trace_events(trace_id, at_ms)")
        db.execSQL("CREATE INDEX idx_trace_rule ON trace_events(rule_id, at_ms DESC)")
        db.execSQL("CREATE INDEX idx_trace_kind ON trace_events(kind, level, at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 has no older format; future migrations must append, never drop user logs.
    }

    private companion object {
        const val NAME = "flowglass-logs.db"
        const val VERSION = 1
    }
}
