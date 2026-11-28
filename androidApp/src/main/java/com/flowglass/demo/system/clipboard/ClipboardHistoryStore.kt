package com.flowglass.demo.system.clipboard

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class ClipboardDatabase(
    context: Context,
    name: String = DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS clipboard_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                changed_at INTEGER NOT NULL,
                changed_at_text TEXT NOT NULL,
                package_name TEXT NOT NULL DEFAULT '',
                user_id INTEGER NOT NULL DEFAULT 0,
                mime_type TEXT NOT NULL DEFAULT 'text/plain',
                label TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL,
                content_length INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'UNKNOWN',
                content_hash TEXT NOT NULL,
                truncated INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_clipboard_changed ON clipboard_entries(changed_at DESC, id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_clipboard_hash ON clipboard_entries(content_hash)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_clipboard_package ON clipboard_entries(package_name, changed_at DESC)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val DB_NAME = "flowglass-clipboard.db"
        const val VERSION = 1
    }
}

internal class ClipboardHistoryStore(
    context: Context,
    databaseName: String = ClipboardDatabase.DB_NAME,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val database = ClipboardDatabase(context, databaseName)

    fun record(entry: ClipboardEntry, dedupeWindowMs: Long = 1_200L): Long? {
        val db = database.writableDatabase
        val hash = sha256(entry.content)
        val existing = db.rawQuery(
            "SELECT id, changed_at, package_name FROM clipboard_entries " +
                "WHERE content_hash = ? ORDER BY changed_at DESC LIMIT 1",
            arrayOf(hash),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                Triple(cursor.getLong(0), cursor.getLong(1), cursor.getString(2))
            } else null
        }
        if (existing != null && entry.changedAt - existing.second in 0 until dedupeWindowMs) {
            // A normal listener may see the copy first and report an empty package; when the
            // system hook delivers the same content moments later, fill in the source package.
            if (entry.packageName.isNotBlank() && existing.third.isBlank()) {
                db.update(
                    "clipboard_entries",
                    ContentValues().apply {
                        put("package_name", entry.packageName)
                        put("user_id", entry.userId)
                        put("source", entry.source.name)
                    },
                    "id = ?",
                    arrayOf(existing.first.toString()),
                )
            }
            return null
        }
        val values = ContentValues().apply {
            put("changed_at", entry.changedAt)
            put("changed_at_text", entry.changedAtText.ifBlank { formatTime(entry.changedAt) })
            put("package_name", entry.packageName)
            put("user_id", entry.userId)
            put("mime_type", entry.mimeType)
            put("label", entry.label)
            put("content", entry.content)
            put("content_length", entry.contentLength)
            put("source", entry.source.name)
            put("content_hash", hash)
            put("truncated", if (entry.truncated) 1 else 0)
        }
        return db.insert("clipboard_entries", null, values).takeIf { it >= 0L }
    }

    fun query(query: ClipboardQuery): ClipboardPage {
        val term = query.search.trim()
        val where = if (term.isEmpty()) "" else
            " WHERE content LIKE ? OR package_name LIKE ? OR label LIKE ?"
        val args = if (term.isEmpty()) emptyArray() else
            arrayOf("%$term%", "%$term%", "%$term%")
        val total = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM clipboard_entries$where",
            args,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val rows = database.readableDatabase.rawQuery(
            "SELECT * FROM clipboard_entries$where " +
                "ORDER BY changed_at DESC, id DESC LIMIT ? OFFSET ?",
            args + arrayOf(query.limit.coerceIn(1, 500).toString(), query.offset.coerceAtLeast(0).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toEntry())
            }
        }
        return ClipboardPage(rows, total)
    }

    fun count(): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM clipboard_entries", null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun delete(id: Long): Int =
        database.writableDatabase.delete("clipboard_entries", "id = ?", arrayOf(id.toString()))

    fun clear() {
        database.writableDatabase.delete("clipboard_entries", null, null)
    }

    fun trim(now: Long = this.now(), retentionDays: Int, maxEntries: Int) {
        val db = database.writableDatabase
        val cutoff = now - retentionDays.coerceAtLeast(0).toLong() * 24L * 60L * 60L * 1_000L
        db.delete("clipboard_entries", "changed_at < ?", arrayOf(cutoff.toString()))
        db.execSQL(
            "DELETE FROM clipboard_entries WHERE id NOT IN " +
                "(SELECT id FROM clipboard_entries ORDER BY changed_at DESC, id DESC LIMIT ?)",
            arrayOf(maxEntries.coerceAtLeast(1).toString()),
        )
    }

    fun close() = database.close()

    private fun Cursor.toEntry(): ClipboardEntry = ClipboardEntry(
        id = getLong(getColumnIndexOrThrow("id")),
        changedAt = getLong(getColumnIndexOrThrow("changed_at")),
        changedAtText = getString(getColumnIndexOrThrow("changed_at_text")),
        packageName = getString(getColumnIndexOrThrow("package_name")),
        userId = getInt(getColumnIndexOrThrow("user_id")),
        mimeType = getString(getColumnIndexOrThrow("mime_type")),
        label = getString(getColumnIndexOrThrow("label")),
        content = getString(getColumnIndexOrThrow("content")),
        contentLength = getInt(getColumnIndexOrThrow("content_length")),
        source = runCatching {
            ClipboardCaptureSource.valueOf(getString(getColumnIndexOrThrow("source")))
        }.getOrDefault(ClipboardCaptureSource.UNKNOWN),
        truncated = getInt(getColumnIndexOrThrow("truncated")) != 0,
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
}
