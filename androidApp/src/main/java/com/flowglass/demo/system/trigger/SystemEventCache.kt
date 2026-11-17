package com.flowglass.demo.system.trigger

import com.flowglass.demo.xposed.SystemHookProtocol

/**
 * App-process cache of the last process state observed by the LSPosed system hook. It is
 * intentionally memory-only: after a process restart the state is unknown until the hook sends
 * the next process event, and a stale entry fails closed instead of pretending the app is running.
 */
internal object SystemEventCache {
    private const val TTL_MS = 120_000L

    private data class Entry(val running: Boolean, val atEpochMs: Long)

    private val lock = Any()
    private val process = HashMap<String, Entry>()

    fun record(event: String, packageName: String, userId: Int, atEpochMs: Long = System.currentTimeMillis()) {
        val running = when (event) {
            SystemHookProtocol.EVENT_PROCESS_STARTED -> true
            SystemHookProtocol.EVENT_PROCESS_ENDED,
            SystemHookProtocol.EVENT_APP_CRASH,
            -> false
            else -> return
        }
        synchronized(lock) {
            process[key(packageName, userId)] = Entry(running, atEpochMs)
        }
    }

    fun isProcessRunning(packageName: String, userId: Int, nowEpochMs: Long = System.currentTimeMillis()): Boolean? {
        synchronized(lock) {
            val entry = process[key(packageName, userId)] ?: return null
            if (nowEpochMs - entry.atEpochMs !in 0..TTL_MS) {
                process.remove(key(packageName, userId))
                return null
            }
            return entry.running
        }
    }

    fun clear() {
        synchronized(lock) { process.clear() }
    }

    private fun key(packageName: String, userId: Int): String =
        if (userId == 0) packageName else "$packageName@$userId"
}
