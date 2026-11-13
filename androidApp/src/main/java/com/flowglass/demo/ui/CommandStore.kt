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

// Shared across instances: a recreation must not read a half-finished write.
private val storeLock = Any()

internal class CommandStore(private val file: File) {
    private val atomic = AtomicFile(file)
    @Volatile private var loaded = false
    @Volatile private var blocked = false
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
                if (decoded.migrated) {
                    LegacyFileMigration.backupBeforeWrite(original, File(file.parentFile, "commands.v1.bak.json")) {
                        writeAtomic(AppStateJson.encode(decoded.state).toByteArray(Charsets.UTF_8))
                    }
                }
                StoreLoad(decoded.state)
            }
        } catch (error: Exception) {
            blocked = true
            // 带上简短原因，现场反馈截图即可定位是哪一类解码失败；不含路径与堆栈。
            val reason = error.message?.replace('\n', ' ')?.take(80)?.takeIf { it.isNotBlank() }
            StoreLoad(null, "本地数据读取失败${reason?.let { "（$it）" }.orEmpty()}，原文件已保留；为避免覆盖，暂不保存修改。")
        }
    }

    fun save(snapshot: PersistedAppState) = synchronized(storeLock) {
        check(loaded && !blocked) { "本地数据尚未安全恢复，已停止写入。" }
        writeAtomic(AppStateJson.encode(snapshot).toByteArray(Charsets.UTF_8))
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
    }
}
