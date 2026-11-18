package com.flowglass.engine

import com.flowglass.engine.trigger.TriggerDispatcher
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerRule
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Engine host tests have no kotlinx-coroutines runtime dependency. The fake executors complete
 * synchronously, so a minimal continuation runner is enough to call the now-suspend dispatcher
 * without adding a test dependency.
 */
internal fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    while (outcome == null) Thread.sleep(1)
    return outcome.getOrThrow()
}

internal fun TriggerDispatcher.dispatchNow(event: TriggerEvent, rules: List<TriggerRule>) =
    runSuspend { dispatch(event, rules) }

internal fun TriggerDispatcher.onRunFinishedNow(rule: TriggerRule) =
    runSuspend { onRunFinished(rule) }
