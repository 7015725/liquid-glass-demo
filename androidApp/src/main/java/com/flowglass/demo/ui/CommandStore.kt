package com.flowglass.demo.ui

import android.util.AtomicFile
import java.io.File
import java.io.InputStreamReader

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
                val text = atomic.openRead().use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8.newDecoder()).readText()
                }
                StoreLoad(AppStateJson.decode(text))
            }
        } catch (_: Exception) {
            blocked = true
            StoreLoad(null, "本地数据读取失败，原文件已保留；为避免覆盖，暂不保存修改。")
        }
    }

    fun save(snapshot: PersistedAppState) = synchronized(storeLock) {
        check(loaded && !blocked) { "本地数据尚未安全恢复，已停止写入。" }
        val bytes = AppStateJson.encode(snapshot).toByteArray(Charsets.UTF_8)
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
