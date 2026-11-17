package com.flowglass.demo.xposed

/**
 * Bounds how often identical app <-> system-hook control messages can be sent. The guard is
 * intentionally time based: after [minIntervalMs] the same message may be sent again so a hook
 * that restarted can still receive the current state.
 */
internal class BroadcastRateLimiter(private val minIntervalMs: Long) {
    private val lastSent = HashMap<String, Long>()

    @Synchronized
    fun allow(key: String, nowEpochMs: Long): Boolean {
        val previous = lastSent[key]
        if (previous != null && nowEpochMs - previous in 0 until minIntervalMs) return false
        lastSent[key] = nowEpochMs
        return true
    }

    @Synchronized
    fun clear() {
        lastSent.clear()
    }
}

/**
 * Drops a state message whose content is exactly the same as the previous one. Unlike
 * [BroadcastRateLimiter] there is no retry interval: applying the same state again cannot add
 * information, and re-applying it is what used to restart the hook heartbeat on every message.
 */
internal class ContentGate {
    private var last: String? = null

    @Synchronized
    fun allow(content: String): Boolean {
        if (content == last) return false
        last = content
        return true
    }

    @Synchronized
    fun clear() {
        last = null
    }
}
