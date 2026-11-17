package com.flowglass.demo.xposed.method

/**
 * App-side replay protection for the Binder channel. A session is established by a signed hello;
 * every later packet must use that session and a strictly increasing sequence number. Timestamps
 * bound how long a captured hello/event can be replayed after an App restart.
 */
internal class MethodHookReplayGuard(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Key(
        val hookId: String,
        val packageName: String,
        val userId: Int,
        val processName: String,
    )

    private data class State(
        val session: String,
        var lastSeq: Long,
        var lastTimestamp: Long,
    )

    private val states = linkedMapOf<Key, State>()

    @Synchronized
    fun hello(key: Key, session: String, timestamp: Long): Boolean {
        if (session.isBlank()) return false
        if (!timestampInWindow(timestamp)) return false
        val previous = states[key]
        if (previous != null && timestamp <= previous.lastTimestamp) return false
        states[key] = State(session, lastSeq = 0L, lastTimestamp = timestamp)
        return true
    }

    @Synchronized
    fun accept(key: Key, session: String, seq: Long, timestamp: Long): Boolean {
        if (seq <= 0L) return false
        if (!timestampInWindow(timestamp)) return false
        val state = states[key] ?: return false
        if (state.session != session) return false
        if (seq <= state.lastSeq) return false
        if (timestamp < state.lastTimestamp) return false
        state.lastSeq = seq
        state.lastTimestamp = timestamp
        return true
    }

    private fun timestampInWindow(timestamp: Long): Boolean {
        val delta = clock() - timestamp
        return delta in -MAX_SKEW_MS..MAX_SKEW_MS
    }

    private companion object {
        const val MAX_SKEW_MS = 5 * 60_000L
    }
}
