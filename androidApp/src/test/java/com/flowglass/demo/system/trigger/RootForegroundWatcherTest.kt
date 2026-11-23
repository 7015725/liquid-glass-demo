package com.flowglass.demo.system.trigger

import com.flowglass.engine.trigger.TriggerSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    @Test
    fun aWatcherStartedBeforeTheRootGrantKeepsRetrying() = runTest {
        val app = RuntimeEnvironment.getApplication()
        var granted = false
        val emitted = mutableListOf<String>()
        val watcher = RootForegroundWatcher(
            context = app,
            scope = this,
            emit = { _, source, data ->
                if (source == TriggerSource.APP) emitted += data["app"].orEmpty()
            },
            screenOn = { true },
            dump = {
                if (granted) "topResumedActivity=ActivityRecord{123 u10 com.clone.app/.MainActivity t42}"
                else null
            },
        )

        watcher.start { true }
        advanceTimeBy(65_000)
        assertTrue("无授权时不应产生事件", emitted.isEmpty())

        granted = true
        advanceTimeBy(65_000)
        assertEquals(listOf("com.clone.app@10"), emitted)
        watcher.stop()
    }
}
