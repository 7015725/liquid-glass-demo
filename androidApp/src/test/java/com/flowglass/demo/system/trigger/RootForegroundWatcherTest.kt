package com.flowglass.demo.system.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootForegroundWatcherTest {
    @Test
    fun parsesTheTopResumedActivityWithCloneUser() {
        val output = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
              Stack #0:
                topResumedActivity=ActivityRecord{1a2b3c u999 com.example.clone/.MainActivity t42}
                mResumedActivity: ActivityRecord{1a2b3c u999 com.example.clone/.MainActivity t42}
        """.trimIndent()

        assertEquals(
            ForegroundApp("com.example.clone", 999),
            RootForegroundWatcher.parseForeground(output),
        )
    }

    @Test
    fun fallsBackToResumedActivityAndMainUser() {
        val output = """
            mResumedActivity: ActivityRecord{abc u0 com.example.main/.Home t1}
        """.trimIndent()

        assertEquals(
            ForegroundApp("com.example.main", 0),
            RootForegroundWatcher.parseForeground(output),
        )
    }

    @Test
    fun ignoresOutputWithoutAnActivityRecord() {
        assertNull(RootForegroundWatcher.parseForeground("No activities"))
    }
}
