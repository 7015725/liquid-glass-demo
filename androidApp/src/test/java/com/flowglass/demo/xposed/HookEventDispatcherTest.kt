package com.flowglass.demo.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HookEventDispatcherTest {
    @Test
    fun boundedQueueDropsTheOldestEvent() {
        val queue = BoundedHookEventQueue(2)
        queue.offer(HookForegroundEvent("a", 0, 1))
        queue.offer(HookForegroundEvent("b", 0, 2))
        queue.offer(HookForegroundEvent("c", 0, 3))

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
            dispatcher.enqueue(HookForegroundEvent("first", 0, 1))
            assertTrue("sender did not start", senderStarted.await(2, TimeUnit.SECONDS))

            // If dispatch held the queue lock while blocked in the sender, this enqueue could not
            // return until releaseSender; a short future timeout catches that regression.
            val enqueueFuture = executor.submit { dispatcher.enqueue(HookForegroundEvent("second", 0, 2)) }
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
            dispatcher.enqueue(HookForegroundEvent("boom", 0, 1))
            dispatcher.enqueue(HookForegroundEvent("ok", 0, 2))
            assertTrue("later event was not delivered", delivered.await(2, TimeUnit.SECONDS))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun onlyResumedActivityMethodsAreHooked() {
        val methods = SystemHookCandidates.resumed.map { it.second }.toSet()
        assertTrue(methods.all { it.contains("Resumed") })
        assertTrue("realStartActivityLocked is not a foreground signal", "realStartActivityLocked" !in methods)
        assertTrue("onActivityAdded is not a foreground signal", "onActivityAdded" !in methods)
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
