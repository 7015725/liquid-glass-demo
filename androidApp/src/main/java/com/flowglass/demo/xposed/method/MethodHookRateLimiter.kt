package com.flowglass.demo.xposed.method

import android.os.SystemClock

/**
 * Per-hook, per-process token bucket. It runs inside the target process before any argument is
 * stringified, so exceeding the limit costs one synchronized counter update, not reflection.
 * The App has a second ingress limiter; this one protects the target app from our own capture.
 */
internal class MethodHookRateLimiter(
    private val maxPerMinute: Int,
    private val burst: Int,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    init {
        require(maxPerMinute in 1..600) { "maxPerMinute out of range" }
        require(burst in 1..maxPerMinute) { "burst out of range" }
    }

    private var tokens = burst.toDouble()
    private var lastRefillMs = clock()
    private var suppressedCount = 0L

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        val elapsed = (now - lastRefillMs).coerceAtLeast(0L)
        if (elapsed > 0) {
            tokens = (tokens + elapsed * maxPerMinute / 60_000.0).coerceAtMost(burst.toDouble())
            lastRefillMs = now
        }
        if (tokens >= 1.0) {
            tokens -= 1.0
            return true
        }
        suppressedCount++
        return false
    }

    @Synchronized
    fun suppressed(): Long = suppressedCount
}
