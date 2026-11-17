package com.flowglass.demo.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HookEventDispatcherTest {
    @Test
    fun boundedQueueDropsTheOldestEvent() {
        val queue = BoundedHookEventQueue<HookEvent>(2)
        queue.offer(HookEvent(SystemHookFamily.FOREGROUND, SystemHookProtocol.EVENT_FOREGROUND, "a", 0, 1))
        queue.offer(HookEvent(SystemHookFamily.FOREGROUND, SystemHookProtocol.EVENT_FOREGROUND, "b", 0, 2))
        queue.offer(HookEvent(SystemHookFamily.FOREGROUND, SystemHookProtocol.EVENT_FOREGROUND, "c", 0, 3))

        assertEquals(listOf("b", "c"), queue.drain().map { it.packageName })
        assertEquals(0, queue.size())
    }

    @Test
    fun dispatcherSendsOnItsOwnThreadWithoutHoldingTheQueueLock() {
        val senderStarted = CountDownLatch(1)
        val releaseSender = CountDownLatch(1)
        val secondSent = CountDownLatch(1)
        val senderThreads = mutableListOf<String>()
        val dispatcher = SystemHookDispatcher(
            capacity = 4,
            log = {},
            sender = { event ->
                synchronized(senderThreads) { senderThreads += Thread.currentThread().name }
                if (event.packageName == "first") {
                    senderStarted.countDown()
                    releaseSender.await(2, TimeUnit.SECONDS)
                } else {
                    secondSent.countDown()
                }
            },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            dispatcher.enqueue(HookEvent(SystemHookFamily.FOREGROUND, SystemHookProtocol.EVENT_FOREGROUND, "first", 0, 1))
            assertTrue("sender did not start", senderStarted.await(2, TimeUnit.SECONDS))

            // If dispatch held the queue lock while blocked in the sender, this enqueue could not
            // return until releaseSender; a short future timeout catches that regression.
            val enqueueFuture = executor.submit { dispatcher.enqueue(HookEvent(SystemHookFamily.FOREGROUND, SystemHookProtocol.EVENT_FOREGROUND, "second", 0, 2)) }
            enqueueFuture.get(1, TimeUnit.SECONDS)

            releaseSender.countDown()
            assertTrue("second event was not delivered", secondSent.await(2, TimeUnit.SECONDS))
            assertTrue(
                "events must be dispatched on the dedicated HandlerThread",
                senderThreads.all { it.startsWith("FlowGlass-HookDispatch") },
            )
        } finally {
            releaseSender.countDown()
            executor.shutdownNow()
            dispatcher.close()
        }
    }

    @Test
    fun senderExceptionDoesNotEscapeAndLaterEventsStillArrive() {
        val delivered = CountDownLatch(1)
        val dispatcher = SystemHookDispatcher(
            capacity = 4,
            log = {},
            sender = { event ->
                if (event.packageName == "boom") error("sender failed")
                delivered.countDown()
            },
        )
        try {
            dispatcher.enqueue(HookEvent(SystemHookFamily.FOREGROUND, SystemHookProtocol.EVENT_FOREGROUND, "boom", 0, 1))
            dispatcher.enqueue(HookEvent(SystemHookFamily.FOREGROUND, SystemHookProtocol.EVENT_FOREGROUND, "ok", 0, 2))
            assertTrue("later event was not delivered", delivered.await(2, TimeUnit.SECONDS))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun candidatesCoverResumedTransitionsAndExcludeBackgroundStarts() {
        val methods = SystemHookCandidates.resumed.map { it.methodName }.toSet()
        assertTrue("setResumedActivityUncheckLocked" in methods)
        assertTrue("setLastResumedActivityUncheckLocked" in methods)
        assertTrue("setResumedActivity" in methods)
        assertTrue("setState" in methods)
        assertTrue("realStartActivityLocked is not a foreground signal", "realStartActivityLocked" !in methods)
        assertTrue("onActivityAdded is not a foreground signal", "onActivityAdded" !in methods)
    }

    @Test
    fun candidateSourcesMatchTheirArgumentLayout() {
        val stateCandidate = SystemHookCandidates.resumed.single {
            it.source == HookEventSource.THIS_ACTIVITY_STATE
        }
        assertEquals("activityRecord.setState", stateCandidate.label)
        assertEquals("ActivityRecord", stateCandidate.className.substringAfterLast('.'))
        assertEquals(
            HookEventSource.FIRST_ARGUMENT_ACTIVITY,
            SystemHookCandidates.resumed.single { it.label == "taskFragment.setResumedActivity" }.source,
        )
    }

    @Test
    fun familyMasksAreAppendOnlyAndRoundTrip() {
        assertEquals(1, SystemHookFamily.FOREGROUND.mask)
        assertEquals(2, SystemHookFamily.PROCESS.mask)
        assertEquals(4, SystemHookFamily.CRASH.mask)
        assertEquals(8, SystemHookFamily.KEYGUARD.mask)
        assertEquals(16, SystemHookFamily.MEDIA.mask)
        assertEquals(32, SystemHookFamily.FOREGROUND_SERVICE.mask)
        val mask = SystemHookFamily.maskOf(
            listOf(SystemHookFamily.FOREGROUND, SystemHookFamily.PROCESS, SystemHookFamily.CRASH),
        )
        assertEquals(setOf(SystemHookFamily.FOREGROUND, SystemHookFamily.PROCESS, SystemHookFamily.CRASH), SystemHookFamily.fromMask(mask))
        assertEquals(emptySet<SystemHookFamily>(), SystemHookFamily.fromMask(0x40))
        assertEquals(SystemHookFamily.FOREGROUND.mask, SystemHookFamily.defaultMask)
    }

    @Test
    fun processCandidatesHaveExplicitSourcesAndFamilies() {
        val methods = SystemHookCandidates.process.map { it.methodName }.toSet()
        assertTrue("startProcessLocked" in methods)
        assertTrue("cleanUpApplicationRecordLocked" in methods)
        assertTrue("handleApplicationCrashInner" in methods)
        assertTrue("appNotResponding" in methods)
        assertTrue(
            "process candidates must carry an explicit identity source",
            SystemHookCandidates.process.all {
                it.source in setOf(
                    HookEventSource.SECOND_ARGUMENT_APPLICATION,
                    HookEventSource.FIRST_ARGUMENT_PROCESS,
                    HookEventSource.SECOND_ARGUMENT_PROCESS,
                    HookEventSource.CLEANUP_PROCESS_RECORD,
                )
            },
        )
        assertTrue(
            "process candidates must be bound to PROCESS or CRASH",
            SystemHookCandidates.process.all {
                it.family == SystemHookFamily.PROCESS || it.family == SystemHookFamily.CRASH
            },
        )
    }

    @Test
    fun identityRejectsMissingOrInvalidValues() {
        assertEquals(HookIdentity("com.example.app", 999), HookIdentityReader.of("com.example.app", 999))
        assertEquals(HookIdentity("com.example.app", 0), HookIdentityReader.of("com.example.app", 0))
        assertEquals(null, HookIdentityReader.of("com.example.app", null))
        assertEquals(null, HookIdentityReader.of(null, 0))
        assertEquals(null, HookIdentityReader.of("not a package", 0))
        assertEquals(null, HookIdentityReader.of("com.example.app", -1))
    }

    @Test
    fun postedRunnableExceptionIsAlsoContained() {
        val handler = Handler(Looper.getMainLooper())
        var logged = ""
        HookFailureGuard.post(handler, "posted callback", log = { logged = it }) {
            throw SecurityException("not ready")
        }

        ShadowLooper.shadowMainLooper().idle()
        assertTrue(logged.contains("posted callback failed"))
        assertTrue(logged.contains("SecurityException"))
    }

    @Test
    fun throwingCallbackIsCaughtAndLogged() {
        var logged = ""
        HookFailureGuard.run("test callback", log = { logged = it }) {
            throw IllegalStateException("boom")
        }

        assertTrue(logged.contains("test callback failed"))
        assertTrue(logged.contains("IllegalStateException"))
        assertTrue(logged.contains("boom"))
    }
}
