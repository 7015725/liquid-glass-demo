package com.flowglass.demo.system.trigger

import android.util.Log
import com.flowglass.engine.trigger.TriggerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicLong

/**
 * Serial trigger lane.
 *
 * Events and control commands are kept in separate channels:
 * - control commands (rule sync, source registration, dispatcher reset) are never dropped and are
 *   checked before every event;
 * - ordinary events use a small bounded queue and may be dropped under a storm, with a throttled
 *   drop counter in logcat.
 *
 * A new worker is created for every host start, so commands left over from a previous start can
 * never run against the next rule set. [stopAfter] lets the host put its dispatcher cleanup on the
 * same lane as dispatch without racing a worker that is still unwinding.
 */
internal class TriggerEventWorker(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val handler: suspend (TriggerEvent) -> Unit,
) {
    private var controls: Channel<suspend () -> Unit> = Channel(Channel.UNLIMITED)
    private var events: Channel<TriggerEvent> = Channel(
        capacity = EVENT_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var job: Job? = null
    private var generation = 0
    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var stopBlock: (suspend () -> Unit)? = null
    private val droppedEvents = AtomicLong()
    private val lastDropLogAt = AtomicLong()

    val isActive: Boolean get() = running && job?.isActive == true

    /** Runs [initializer] before the first queued command, then processes commands serially. */
    fun start(initializer: suspend () -> Unit = {}) {
        if (running) return
        // Recreate both channels on every start: a stop() must not leave stale commands that a
        // later start would execute against the new rule set.
        generation += 1
        val myGeneration = generation
        controls = Channel(Channel.UNLIMITED)
        events = Channel(capacity = EVENT_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        running = true
        stopping = false
        stopBlock = null
        job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            try {
                if (generation != myGeneration || !running) return@launch
                runSafely { initializer() }
                var pendingEvent: TriggerEvent? = null
                while (running && generation == myGeneration) {
                    if (stopping) break
                    val control = controls.tryReceive().getOrNull()
                    if (control != null) {
                        runSafely(control)
                        continue
                    }
                    val event = pendingEvent
                    if (event != null) {
                        pendingEvent = null
                        val lateControl = controls.tryReceive().getOrNull()
                        if (lateControl != null) {
                            pendingEvent = event
                            runSafely(lateControl)
                        } else {
                            runSafely { handler(event) }
                        }
                        continue
                    }

                    var selectedControl: (suspend () -> Unit)? = null
                    var selectedEvent: TriggerEvent? = null
                    select<Unit> {
                        controls.onReceiveCatching { result ->
                            if (result.isSuccess) selectedControl = result.getOrNull()
                        }
                        events.onReceiveCatching { result ->
                            if (result.isSuccess) selectedEvent = result.getOrNull()
                        }
                    }
                    if (selectedControl != null) {
                        runSafely(selectedControl!!)
                    } else if (selectedEvent != null) {
                        pendingEvent = selectedEvent
                    }
                }
                if (generation == myGeneration) {
                    val finalizer = stopBlock
                    stopBlock = null
                    if (finalizer != null) runSafely(finalizer)
                }
            } finally {
                if (generation == myGeneration) {
                    running = false
                    job = null
                }
            }
        }.also { it.start() }
    }

    /** Queues an event without blocking the caller (usually a receiver or binder thread). */
    fun emit(event: TriggerEvent) {
        if (!running || stopping) return
        val result = events.trySend(event)
        if (result.isFailure) recordDroppedEvent()
    }

    /** Queues a non-droppable control command (rule sync, source registration, dispatcher call). */
    fun submit(block: suspend () -> Unit) {
        if (!running || stopping) return
        controls.trySend(block)
    }

    /**
     * Stops accepting work, abandons queued events/commands, and runs [finalizer] on the serial
     * lane after the command currently in flight. Returns false when no worker is active, in
     * which case the caller must run its cleanup directly.
     */
    fun stopAfter(finalizer: suspend () -> Unit): Boolean {
        if (!running) return false
        stopBlock = finalizer
        stopping = true
        // Wake an idle select loop so it can reach the finalizer immediately.
        controls.trySend {}
        return true
    }

    /** Hard stop used by tests/teardown; does not run a finalizer. */
    fun stop() {
        running = false
        stopping = true
        generation += 1
        stopBlock = null
        controls.close()
        events.close()
        job?.cancel()
        job = null
    }

    private suspend fun runSafely(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // One bad trigger/control command must not kill the serial lane for every later event.
            Log.e(TAG, "trigger command failed", failure)
        }
    }

    private fun recordDroppedEvent() {
        val dropped = droppedEvents.incrementAndGet()
        val now = System.currentTimeMillis()
        val previous = lastDropLogAt.get()
        if (now - previous >= DROP_LOG_INTERVAL_MS && lastDropLogAt.compareAndSet(previous, now)) {
            Log.w(TAG, "trigger event queue overflow; dropped=$dropped (summary every 10s)")
        }
    }

    private companion object {
        const val TAG = "FlowTriggerWorker"
        const val EVENT_CAPACITY = 64
        const val DROP_LOG_INTERVAL_MS = 10_000L
    }
}
