package com.flowglass.demo.xposed.method

import android.util.AtomicFile
import com.flowglass.engine.methodhook.MethodHookFile
import com.flowglass.engine.methodhook.MethodHookJson
import com.flowglass.engine.methodhook.MethodHookSpec
import java.io.File

internal data class MethodHookLoad(
    val hooks: List<MethodHookSpec>,
    val error: String? = null,
    val writable: Boolean = true,
)

/**
 * Separate from commands.json on purpose: method hooks have their own schema and must not make an
 * existing command document unreadable if a future hook field is unknown to an older build.
 * A corrupt file fails closed: it is never overwritten, and the UI can still explain the problem.
 */
internal class MethodHookStore(private val file: File) {
    private val atomic = AtomicFile(file)
    @Volatile private var loaded = false
    @Volatile private var blocked = false

    fun load(): MethodHookLoad {
        loaded = true
        blocked = false
        return try {
            if (!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) {
                MethodHookLoad(emptyList())
            } else {
                val bytes = atomic.openRead().use { it.readBytes() }
                val text = bytes.toString(Charsets.UTF_8)
                val decoded = MethodHookJson.decode(text)
                MethodHookLoad(decoded.hooks)
            }
        } catch (failure: Exception) {
            blocked = true
            val reason = failure.message?.replace('\n', ' ')?.take(100)?.takeIf { it.isNotBlank() }
            MethodHookLoad(
                hooks = emptyList(),
                error = "方法钩子配置读取失败${reason?.let { "（$it）" }.orEmpty()}，原文件已保留。",
                writable = false,
            )
        }
    }

    fun save(hooks: List<MethodHookSpec>) {
        check(loaded && !blocked) { "方法钩子配置尚未安全恢复，已停止写入" }
        val stream = atomic.startWrite()
        try {
            stream.write(MethodHookJson.encode(MethodHookFile(hooks = hooks)).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
