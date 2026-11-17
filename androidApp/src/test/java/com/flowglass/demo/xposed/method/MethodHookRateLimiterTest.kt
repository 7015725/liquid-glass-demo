package com.flowglass.demo.xposed.method

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodHookRateLimiterTest {
    @Test
    fun burstIsConsumedThenRefillsAtTheConfiguredRate() {
        var now = 0L
        val limiter = MethodHookRateLimiter(maxPerMinute = 60, burst = 2, clock = { now })
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertEquals(1, limiter.suppressed())

        now = 999L
        assertFalse(limiter.tryAcquire())
        now = 1_000L
        assertTrue(limiter.tryAcquire())
        assertEquals(2, limiter.suppressed())
    }

    @Test
    fun separateHooksDoNotShareTokens() {
        var now = 0L
        val first = MethodHookRateLimiter(60, 1, clock = { now })
        val second = MethodHookRateLimiter(60, 1, clock = { now })
        assertTrue(first.tryAcquire())
        assertFalse(first.tryAcquire())
        assertTrue(second.tryAcquire())
        now = 1_000L
        assertTrue(first.tryAcquire())
    }
}
