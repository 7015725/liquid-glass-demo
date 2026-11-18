package com.flowglass.demo.system.trigger

import android.util.Log
import com.flowglass.engine.trigger.TriggerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serial event/command worker for trigger dispatch.
 *
 * A single [CoroutineDispatcher] lane is used for both rule reconciliation and event handling,
 * so a trigger can never run its conditions on the main thread and two events can never be
 * processed out of order. The handler is allowed to suspend (Root/file checks do), but the next
 * command is only started after the previous one has completed.
 */
internal class TriggerEventWorker(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val handler: suspend (TriggerEvent) -> Unit,
) {
    private val commands = Channel<suspend () -> Unit>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var job: Job? = null
    @Volatile private var running = false

    /** Runs [initializer] before the first queued command, then processes commands serially. */
    fun start(initializer: suspend () -> Unit = {}) {
        if (running) return
        running = true
        job = scope.launch(dispatcher) {
            if (!running) return@launch
            initializer()
            if (!running) return@launch
            for (command in commands) {
                if (!running) break
                try {
                    command()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // One bad trigger must not kill the serial lane for every later event.
                    Log.e(TAG, "trigger command failed", failure)
                }
            }
        }
    }

    /** Queues an event without blocking the caller (usually a receiver or binder thread). */
    fun emit(event: TriggerEvent) {
        if (!running) return
        commands.trySend { handler(event) }
    }

    /** Queues a rule/source reconciliation on the same serial lane as event dispatch. */
    fun submit(block: suspend () -> Unit) {
        if (!running) return
        commands.trySend(block)
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "FlowTriggerWorker"
    }
}
