package com.flowglass.demo.xposed

import android.os.Handler
import android.os.HandlerThread
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only data a hook callback is allowed to carry. No Context, Intent or system object is
 * retained in the queue: the worker builds the broadcast after the callback has returned.
 */
internal data class HookEvent(
    val family: SystemHookFamily,
    val kind: String,
    val packageName: String? = null,
    val userId: Int? = null,
    val atEpochMs: Long = System.currentTimeMillis(),
    /** Clipboard body; null for every non-clipboard event. */
    val text: String? = null,
    val mimeType: String? = null,
    val label: String? = null,
)

/**
 * Bounded event buffer. When the producer is faster than the worker, the oldest event is
 * discarded; recent transitions are more useful than stale ones.
 */
internal class BoundedHookEventQueue<T>(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val events = ArrayDeque<T>(capacity)
    private var dropped = 0

    fun offer(event: T) {
        synchronized(lock) {
            if (events.size >= capacity) {
                events.removeFirst()
                dropped += 1
            }
            events.addLast(event)
        }
    }

    fun droppedCount(): Int = synchronized(lock) { dropped }

    fun drain(): List<T> = synchronized(lock) {
        if (events.isEmpty()) return@synchronized emptyList()
        val snapshot = events.toList()
        events.clear()
        snapshot
    }

    fun size(): Int = synchronized(lock) { events.size }
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
 * are often held by the hooked methods. Every family has its own bounded queue, so a burst of
 * high-frequency events cannot evict foreground/lifecycle transitions.
 */
internal class SystemHookDispatcher(
    private val capacity: Int = 32,
    private val log: (String) -> Unit,
    private val sender: (HookEvent) -> Unit,
) {
    private val queues: Map<SystemHookFamily, BoundedHookEventQueue<HookEvent>> =
        SystemHookFamily.entries.associateWith { BoundedHookEventQueue<HookEvent>(capacity) }
    private val workerLock = Any()
    private val scheduled = AtomicBoolean(false)
    @Volatile private var worker: HandlerThread? = null
    @Volatile private var handler: Handler? = null

    fun enqueue(event: HookEvent) {
        val queue = queues.getValue(event.family)
        val before = queue.droppedCount()
        queue.offer(event)
        val dropped = queue.droppedCount()
        if (dropped > before && dropped % 32 == 0) {
            runCatching { log("hook queue overloaded; family=${event.family} dropped=$dropped") }
        }
        schedule()
    }

    /** Total events evicted by the per-family bounded queues since process start. */
    fun droppedEvents(): Int = queues.values.sumOf { it.droppedCount() }

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
                scheduled.set(false)
            }
        }
    }

    private fun schedule() {
        HookFailureGuard.run("schedule hook dispatch", log = log) {
            ensureWorker()
            if (!scheduled.compareAndSet(false, true)) return@run
            val target = handler
            if (target == null) {
                scheduled.set(false)
                return@run
            }
            target.post {
                HookFailureGuard.run("dispatch hook events", log = log) {
                    try {
                        drainAll()
                    } finally {
                        scheduled.set(false)
                        if (hasEvents()) schedule()
                    }
                }
            }
        }
    }

    private fun drainAll() {
        var delivered = true
        var batches = 0
        while (delivered && batches < MAX_DRAIN_BATCHES) {
            delivered = false
            batches++
            for (family in SystemHookFamily.entries) {
                val batch = queues.getValue(family).drain()
                if (batch.isEmpty()) continue
                delivered = true
                batch.forEach { item ->
                    HookFailureGuard.run("send hook event", log = log) { sender(item) }
                }
            }
        }
    }

    private fun hasEvents(): Boolean =
        SystemHookFamily.entries.any { queues.getValue(it).size() > 0 }

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
        const val MAX_DRAIN_BATCHES = 8
    }
}
