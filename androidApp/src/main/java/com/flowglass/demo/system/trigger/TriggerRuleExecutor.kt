package com.flowglass.demo.system.trigger

import com.flowglass.demo.ui.Cmd
import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerExecutor
import com.flowglass.engine.trigger.TriggerRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * The dispatcher and all condition checks stay on the serial worker. Only the AppState operations
 * cross to [mainDispatcher]: Compose snapshot state must be read and written on the main thread.
 *
 * The decision data used by [TriggerDispatcher] (isRunning/activeCount) and execute() itself are
 * suspend, so the worker is suspended while the main thread does the short state mutation. This
 * avoids a second copy of the repeat policy and keeps execute()'s return value authoritative.
 */
internal class TriggerRuleExecutor(
    private val mainDispatcher: CoroutineDispatcher,
    private val find: (Long) -> Cmd?,
    private val activeCountFor: (Long) -> Int,
    private val cancelFor: (Long) -> Unit,
    private val startFor: (Cmd, TriggerRule, String, Map<String, Any?>) -> Job?,
    private val session: () -> Long = { 0L },
    private val onFinished: (TriggerRule, Long) -> Unit,
) : TriggerExecutor {
    override suspend fun isRunning(rule: TriggerRule): Boolean = withContext(mainDispatcher) {
        activeCountFor(rule.cmdId) > 0
    }

    override suspend fun activeCount(rule: TriggerRule): Int = withContext(mainDispatcher) {
        activeCountFor(rule.cmdId)
    }

    override suspend fun cancel(rule: TriggerRule) = withContext(mainDispatcher) {
        cancelFor(rule.cmdId)
    }

    override suspend fun execute(
        rule: TriggerRule,
        binding: TriggerBinding,
        reason: String,
        variables: Map<String, Any?>,
    ): Boolean = withContext(mainDispatcher) {
        val cmd = find(rule.cmdId) ?: return@withContext false
        val runSession = session()
        val job = startFor(cmd, rule, reason, variables) ?: return@withContext false
        job.invokeOnCompletion {
            // Completion may arrive on any thread; the host puts it back on the serial worker.
            onFinished(rule, runSession)
        }
        true
    }
}
