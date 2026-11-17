package com.flowglass.demo.ui

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression for the cold-start crash on 0.8.0-alpha3:
 *
 * `AppRuntime.load()` used to construct AppState from a Dispatchers.IO coroutine while the
 * first composition snapshot was open. AppState owns many snapshot states (e.g. query), and
 * reading those states during composition threw "Reading a state that was created after the
 * snapshot was taken or in a snapshot that has not yet been applied".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupSnapshotTest {
    private val worker = Executors.newSingleThreadExecutor()

    @After
    fun stopWorker() {
        worker.shutdownNow()
    }

    @Test
    fun composeStateCreatedOnWorkerCannotBeReadFromAnEarlierSnapshot() {
        val snapshot = Snapshot.takeSnapshot()
        try {
            val state = worker.submit<AppState> { AppState() }.get(5, TimeUnit.SECONDS)
            val failure = try {
                snapshot.enter { state.query }
                null
            } catch (error: IllegalStateException) {
                error
            }
            assertTrue(
                "expected Compose snapshot state error, got $failure",
                failure?.message?.contains("Reading a state that was created") == true,
            )
        } finally {
            snapshot.dispose()
        }
    }

    @Test
    fun runtimeBuildsStateOnMainEvenWhenLoadStartsOnAWorker() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "commands.json").delete()
        val runtime = AppRuntime.of(context)
        runtime.resetForTest()

        val result = worker.submit<AppState> { runtime.load() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!result.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val state = result.get(2, TimeUnit.SECONDS)

        assertTrue("AppState must be constructed on main", runtime.stateCreatedOnMainThread)
        assertNull(state.query)
    }

    @Test
    fun concurrentLoadsParseStoreOnceAndShareOneAppState() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "commands.json").delete()
        val runtime = AppRuntime.of(context)
        runtime.resetForTest()

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            fun submitLoad() = pool.submit<AppState> {
                start.await()
                runtime.load()
            }
            val first = submitLoad()
            val second = submitLoad()
            start.countDown()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while ((!first.isDone || !second.isDone) && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            val a = first.get(2, TimeUnit.SECONDS)
            val b = second.get(2, TimeUnit.SECONDS)

            assertSame("both callers must receive the same process-wide state", a, b)
            assertEquals("store.load() must run exactly once", 1, runtime.storeLoadCount)
        } finally {
            pool.shutdownNow()
        }
    }
}
