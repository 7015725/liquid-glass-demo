package com.flowglass.demo.xposed

import android.os.Handler
import android.os.HandlerThread
import java.util.ArrayDeque

/**
 * The only data a foreground hook is allowed to carry. No Context, Intent or system object is
 * retained in the queue: the worker builds the broadcast after the hook callback has returned.
 */
internal data class HookForegroundEvent(
    val packageName: String,
    val userId: Int,
    val atEpochMs: Long,
)

/**
 * Bounded event buffer. When the producer is faster than the worker, the oldest event is
 * discarded; recent foreground transitions are more useful than stale ones.
 */
internal class BoundedHookEventQueue(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val events = ArrayDeque<HookForegroundEvent>(capacity)

    fun offer(event: HookForegroundEvent) {
        synchronized(lock) {
            if (events.size >= capacity) events.removeFirst()
            events.addLast(event)
        }
    }

    fun drain(): List<HookForegroundEvent> = synchronized(lock) {
        if (events.isEmpty()) return@synchronized emptyList()
        val snapshot = events.toList()
        events.clear()
        snapshot
    }

    fun size(): Int = synchronized(lock) { events.size }
}

/**
 * Candidate methods that represent a real resumed-activity transition. Background starts
 * (realStartActivityLocked/onActivityAdded) are intentionally excluded: they can create a
 * foreground event before the activity is actually resumed.
 */
internal object SystemHookCandidates {
    val resumed = listOf(
        "com.android.server.wm.ActivityTaskManagerService" to "setResumedActivity",
        "com.android.server.wm.ActivityTaskManagerService" to "updateResumedActivity",
        "com.android.server.am.ActivityManagerService" to "setResumedActivity",
    )
}

/**
 * Runs a system-server side callback without allowing any failure to escape. Logging is also
 * guarded because a broken Xposed logger must not turn a handled hook failure into a crash.
 */
internal object HookFailureGuard {
    fun run(where: String, log: (String) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            runCatching {
                log("$where failed: ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
    }

    /** Protect both the post() call and the Runnable that will execute on the target Looper. */
    fun post(handler: Handler, where: String, log: (String) -> Unit, block: () -> Unit) {
        run("post $where", log) {
            handler.post {
                run(where, log, block)
            }
        }
    }
}

/**
 * Serializes hook events onto a dedicated HandlerThread. Hook callbacks only [enqueue] data;
 * the protected broadcast is sent later, outside the WindowManager/ActivityManager locks that
 * are often held by the hooked methods. A bounded queue prevents an unbounded backlog.
 */
internal class SystemHookDispatcher(
    private val capacity: Int = 32,
    private val log: (String) -> Unit,
    private val sender: (HookForegroundEvent) -> Unit,
) {
    private val queue = BoundedHookEventQueue(capacity)
    private val workerLock = Any()
    @Volatile private var worker: HandlerThread? = null
    @Volatile private var handler: Handler? = null

    fun enqueue(event: HookForegroundEvent) {
        queue.offer(event)
        HookFailureGuard.run("schedule hook dispatch", log = log) {
            ensureWorker()
            handler?.post {
                HookFailureGuard.run("dispatch hook events", log = log) {
                    queue.drain().forEach { item ->
                        HookFailureGuard.run("send hook event", log = log) { sender(item) }
                    }
                }
            }
        }
    }

    /** Start the worker before the first hook callback, not while a WMS/AMS lock may be held. */
    fun warmUp() {
        HookFailureGuard.run("start hook dispatcher", log = log) { ensureWorker() }
    }

    fun close() {
        HookFailureGuard.run("close hook dispatcher", log = log) {
            synchronized(workerLock) {
                worker?.quitSafely()
                worker = null
                handler = null
            }
        }
    }

    private fun ensureWorker() {
        if (handler != null) return
        synchronized(workerLock) {
            if (handler != null) return
            val created = HandlerThread(WORKER_NAME)
            created.start()
            worker = created
            handler = Handler(created.looper)
        }
    }

    private companion object {
        const val WORKER_NAME = "FlowGlass-HookDispatch"
    }
}
