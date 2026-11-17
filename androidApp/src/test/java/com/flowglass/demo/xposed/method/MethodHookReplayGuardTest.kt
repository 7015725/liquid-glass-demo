package com.flowglass.demo.xposed.method

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodHookReplayGuardTest {
    @Test
    fun helloThenStrictlyIncreasingEventsAreAcceptedOnce() {
        var now = 10_000L
        val guard = MethodHookReplayGuard { now }
        val key = MethodHookReplayGuard.Key("h1", "com.example", 0, "com.example")
        assertFalse(guard.accept(key, "s1", 1, now))
        assertTrue(guard.hello(key, "s1", now))
        assertTrue(guard.accept(key, "s1", 1, now))
        assertFalse(guard.accept(key, "s1", 1, now))
        assertFalse(guard.accept(key, "s1", 0, now))
        now += 1
        assertTrue(guard.accept(key, "s1", 2, now))
        assertFalse(guard.accept(key, "s2", 3, now))
    }

    @Test
    fun newSessionNeedsANewerTimestampAndOldSessionCannotReturn() {
        var now = 20_000L
        val guard = MethodHookReplayGuard { now }
        val key = MethodHookReplayGuard.Key("h1", "com.example", 0, "proc")
        assertTrue(guard.hello(key, "old", now))
        assertTrue(guard.accept(key, "old", 1, now))

        assertFalse(guard.hello(key, "new", now))
        now += 1
        assertTrue(guard.hello(key, "new", now))
        assertTrue(guard.accept(key, "new", 1, now))
        assertFalse(guard.accept(key, "old", 2, now))
    }

    @Test
    fun timestampsOutsideTheWindowAreRejected() {
        val guard = MethodHookReplayGuard { 1_000_000L }
        val key = MethodHookReplayGuard.Key("h1", "com.example", 0, "proc")
        assertFalse(guard.hello(key, "s1", 1_000_000L - 300_001L))
        assertTrue(guard.hello(key, "s1", 1_000_000L - 10_000L))
    }
}
