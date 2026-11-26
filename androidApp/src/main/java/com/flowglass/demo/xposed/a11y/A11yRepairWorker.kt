package com.flowglass.demo.xposed.a11y

import com.flowglass.demo.system.a11y.A11yKeepAlivePolicy

/**
 * Serialises repair attempts for a disconnected accessibility service.
 *
 * One attempt per component at a time, exponential backoff, a hard cap per window, and a verify
 * step that turns "bind returned" into "the service is actually connected".
 */
internal class A11yRepairWorker(
    private val ops: A11yConnectionOps,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    private val onStatus: (component: String, state: String, reason: String) -> Unit,
    private val maxAttempts: Int = 3,
    private val attemptWindowMs: Long = 10 * 60_000L,
    private val backoffMs: LongArray = longArrayOf(500L, 1_500L, 3_000L),
    private val verifyDelayMs: Long = 1_500L,
) {
    private val lock = Any()
    private val attempts = HashMap<String, MutableList<Long>>()
    private val pending = HashSet<String>()

    fun onDisconnected(component: String, connection: Any) = scheduleAttempt(component, connection)

    private fun scheduleAttempt(component: String, connection: Any) {
        synchronized(lock) {
            if (!pending.add(component)) return
            val history = history(component)
            if (history.size >= maxAttempts) {
                pending.remove(component)
                onStatus(component, "limit", "limit")
                return
            }
            val delay = backoffMs[history.size.coerceAtMost(backoffMs.lastIndex)]
            schedule(delay) { runAttempt(component, connection) }
        }
    }

    private fun runAttempt(component: String, connection: Any) {
        synchronized(lock) { pending.remove(component) }
        val attemptsSoFar = synchronized(lock) { history(component).size }
        val decision = runCatching { ops.decide(component, connection, attemptsSoFar) }
            .getOrElse { A11yKeepAlivePolicy.Result(false, "error") }
        if (!decision.rebind) {
            onStatus(component, "skipped", decision.reason)
            return
        }
        synchronized(lock) { history(component) += now() }
        onStatus(component, "repairing", "")
        log("rebind $component attempt=${attemptsSoFar + 1}")
        if (!runCatching { ops.bind(connection) }.getOrDefault(false)) {
            onStatus(component, "error", "bind_failed")
            return
        }
        schedule(verifyDelayMs) { verify(component, connection) }
    }

    private fun verify(component: String, connection: Any) {
        if (runCatching { ops.isAlive(connection) }.getOrDefault(false)) {
            synchronized(lock) { attempts.remove(component) }
            onStatus(component, "repaired", "")
            runCatching { ops.onRepaired(component) }
        } else {
            onStatus(component, "disconnected", "verify_failed")
            scheduleAttempt(component, connection)
        }
    }

    private fun history(component: String): MutableList<Long> {
        val list = attempts.getOrPut(component) { mutableListOf() }
        list.removeAll { now() - it > attemptWindowMs }
        return list
    }
}
