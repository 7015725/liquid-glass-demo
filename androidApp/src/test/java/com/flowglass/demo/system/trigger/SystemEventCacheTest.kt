package com.flowglass.demo.system.trigger

import com.flowglass.demo.xposed.SystemHookProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemEventCacheTest {
    @Test
    fun startEndAndCrashUpdateTheProcessState() {
        SystemEventCache.clear()
        val now = 1_000_000L
        SystemEventCache.record(SystemHookProtocol.EVENT_PROCESS_STARTED, "com.example.app", 10, now)
        assertEquals(true, SystemEventCache.isProcessRunning("com.example.app", 10, now + 1))

        SystemEventCache.record(SystemHookProtocol.EVENT_PROCESS_ENDED, "com.example.app", 10, now + 2)
        assertEquals(false, SystemEventCache.isProcessRunning("com.example.app", 10, now + 3))

        SystemEventCache.record(SystemHookProtocol.EVENT_PROCESS_STARTED, "com.example.app", 10, now + 4)
        SystemEventCache.record(SystemHookProtocol.EVENT_APP_CRASH, "com.example.app", 10, now + 5)
        assertEquals(false, SystemEventCache.isProcessRunning("com.example.app", 10, now + 6))
    }

    @Test
    fun anrDoesNotInventAProcessExit() {
        SystemEventCache.clear()
        SystemEventCache.record(SystemHookProtocol.EVENT_PROCESS_STARTED, "com.example.app", 0, 1_000)
        SystemEventCache.record(SystemHookProtocol.EVENT_APP_ANR, "com.example.app", 0, 2_000)
        assertEquals(true, SystemEventCache.isProcessRunning("com.example.app", 0, 2_001))
    }

    @Test
    fun usersAreIndependentAndStaleEntriesFailClosed() {
        SystemEventCache.clear()
        SystemEventCache.record(SystemHookProtocol.EVENT_PROCESS_STARTED, "com.example.app", 0, 10)
        SystemEventCache.record(SystemHookProtocol.EVENT_PROCESS_STARTED, "com.example.app", 10, 10)
        assertEquals(true, SystemEventCache.isProcessRunning("com.example.app", 0, 11))
        assertEquals(true, SystemEventCache.isProcessRunning("com.example.app", 10, 11))
        assertNull(SystemEventCache.isProcessRunning("com.example.app", 11, 11))
        assertNull(SystemEventCache.isProcessRunning("com.example.app", 0, 10 + 120_001))
        assertNull(SystemEventCache.isProcessRunning("com.example.app", 0, 20))
    }
}
