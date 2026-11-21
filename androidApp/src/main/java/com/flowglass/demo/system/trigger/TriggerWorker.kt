package com.flowglass.demo.system.trigger

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The single thread that owns every trigger-side state change: event collection, condition
 * evaluation, dispatcher bookkeeping and run completions. Rule bodies still execute on the engine's
 * own dispatcher; this thread only observes them, so a blocking Root probe stalls triggers instead
 * of the UI and no two callbacks touch the dispatcher or the execution maps at the same time.
 */
internal object TriggerWorker {
    /** Visible in traces and in the threading test. */
    const val THREAD_NAME = "flowglass-trigger"

    val dispatcher: CoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable -> Thread(runnable, THREAD_NAME).apply { isDaemon = true } }
        .asCoroutineDispatcher()

    /** Outlives one host instance so a stop request can still serialise behind an in-flight run. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
