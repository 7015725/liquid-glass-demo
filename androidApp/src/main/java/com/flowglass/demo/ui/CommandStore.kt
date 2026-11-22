package com.flowglass.demo.ui

import android.util.AtomicFile
import java.io.File
import java.io.InputStreamReader
import com.flowglass.engine.LegacyFileMigration

internal data class StoreLoad(val saved: PersistedAppState?, val error: String? = null) {
    fun appState(): AppState = AppState(
        saved ?: if (error == null) null else PersistedAppState(emptyList(), emptyList(), emptyList(), true, 1)
    ).also { it.storageError = error }
}

/** The old type id is intentionally not migrated; a pre-release build may delete it. */
internal fun PersistedAppState.hasRetiredMethodHookTrigger(): Boolean =
    (autoCmds + oneTaps).any { command ->
        command.triggers.any { it.typeId == "trigger.method_hook" }
    }

// Shared across instances: a recreation must not read a half-finished write.
private val storeLock = Any()

internal class CommandStore(private val file: File) {
    private val atomic = AtomicFile(file)
    @Volatile private var loaded = false
    @Volatile private var blocked = false
    // Guarded by storeLock for writes; volatile so requestSave can capture it off the UI thread.
    @Volatile private var epoch = 0L
    // UI flushes must never wait for an ongoing disk transaction's lock.
    val canSave: Boolean get() = loaded && !blocked

    fun load(): StoreLoad = synchronized(storeLock) {
        loaded = true
        blocked = false
        try {
            if (!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) {
                StoreLoad(null)
            } else {
                val original = atomic.openRead().use { it.readBytes() }
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(original)).toString()
                val decoded = AppStateJson.decodeDocument(text)
                if (decoded.state.hasRetiredMethodHookTrigger()) {
                    // Pre-release format changed without a migration: fail closed with a clear
                    // recovery instruction instead of showing a half-working old trigger.
                    blocked = true
                    return StoreLoad(null, "数据格式已更新，请清除应用数据或重新安装")
                }
                if (decoded.migrated) {
                    val backup = File(file.parentFile, decoded.backupName ?: "commands.v1.bak.json")
                    LegacyFileMigration.backupBeforeWrite(original, backup) {
                        writeAtomic(AppStateJson.encode(decoded.state).toByteArray(Charsets.UTF_8))
                    }
                }
                StoreLoad(decoded.state)
            }
        } catch (error: Exception) {
            blocked = true
            if (error.message?.contains("数据格式已更新") == true) {
                return@synchronized StoreLoad(
                    null,
                    "数据格式已更新，请清除应用数据或重新安装。原文件已保留；为避免覆盖，暂不保存修改。",
                )
            }
            // 带上简短原因，现场反馈截图即可定位是哪一类解码失败；不含路径与堆栈。
            val reason = error.message?.replace('\n', ' ')?.take(80)?.takeIf { it.isNotBlank() }
            StoreLoad(null, "本地数据读取失败${reason?.let { "（$it）" }.orEmpty()}，原文件已保留；为避免覆盖，暂不保存修改。")
        }
    }

    /**
     * Writes [snapshot] unless [epochToken] was captured before a restore transaction began.
     * Returns false when the save is stale and was deliberately dropped.
     */
    fun save(snapshot: PersistedAppState, epochToken: Long = -1L): Boolean = synchronized(storeLock) {
        if (epochToken >= 0L && epochToken != epoch) return@synchronized false
        check(loaded && !blocked) { "本地数据尚未安全恢复，已停止写入。" }
        writeAtomic(AppStateJson.encode(snapshot).toByteArray(Charsets.UTF_8))
        true
    }

    /** Monotonic token for saves; bumped whenever the store enters or leaves a restore. */
    fun currentEpoch(): Long = epoch

    /**
     * First half of the restore transaction: validates and durably writes [candidate] while
     * ordinary saving stays blocked, so a flush during the window cannot queue the old document
     * behind this write. Call [completeRepair] only after the candidate is live.
     * Returns the preserved original file, or null when there was nothing to preserve.
     */
    fun repair(candidate: PersistedAppState): File? = synchronized(storeLock) {
        val encoded = AppStateJson.encode(candidate).toByteArray(Charsets.UTF_8)
        val preserved = preserveOriginal()
        writeAtomic(encoded)
        val written = atomic.openRead().use { it.readBytes() }
        check(written.contentEquals(encoded)) {
            if (preserved != null) {
                "修复写入校验失败；原始字节已保留在 ${preserved.name}。"
            } else {
                "修复写入校验失败。"
            }
        }
        preserved
    }

    /**
     * Blocks ordinary saving for the whole restore transaction, including healthy stores where
     * saving was allowed before. Returns the previous writability so a failed restore can restore it.
     */
    fun beginRepair(): Boolean = synchronized(storeLock) {
        val couldSave = loaded && !blocked
        epoch += 1
        loaded = true
        blocked = true
        couldSave
    }

    /** Ends a restore that never became live and restores the previous writability. */
    fun abortRepair(couldSave: Boolean) = synchronized(storeLock) {
        epoch += 1
        loaded = true
        blocked = !couldSave
    }

    /** Second half of the restore transaction: resumes ordinary saving for the live document. */
    fun completeRepair() = synchronized(storeLock) {
        epoch += 1
        loaded = true
        blocked = false
    }

    /** Best-effort rollback when the restored document was written but never became live. */
    fun rollbackTo(preserved: File) = synchronized(storeLock) {
        epoch += 1
        writeAtomic(preserved.readBytes())
    }

    /**
     * Copies the current bytes (base file or its AtomicFile backup) to a unique repair file.
     * Fails closed: if the source exists but cannot be read, the restore must abort instead of
     * overwriting bytes that were never preserved.
     */
    private fun preserveOriginal(): File? {
        val source = listOf(file, File("${file.path}.bak")).firstOrNull { it.exists() } ?: return null
        val bytes = source.readBytes()
        if (bytes.isEmpty()) return null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val target = File(
            file.parentFile,
            "commands.before-repair-$stamp-${java.util.UUID.randomUUID()}.json",
        )
        target.writeBytes(bytes)
        check(target.readBytes().contentEquals(bytes)) { "原始字节保留校验失败，已停止恢复。" }
        return target
    }

    private fun writeAtomic(bytes: ByteArray) {
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
        // AOSP 的 AtomicFile.finishWrite 在 fsync 或 rename 失败时只写日志、不抛异常；
        // 以读回字节作为「提交成功」的判据，避免把旧文件当成新状态继续用。
        verifyCommitted({ atomic.openRead().use { it.readBytes() } }, bytes)
    }
}

/** AtomicFile 的 finishWrite 可能静默失败，读回不一致必须当成写入失败。 */
internal fun verifyCommitted(read: () -> ByteArray, expected: ByteArray) {
    val actual = read()
    if (!actual.contentEquals(expected)) {
        throw java.io.IOException("本地数据提交校验失败：磁盘内容与待写入内容不一致")
    }
}
