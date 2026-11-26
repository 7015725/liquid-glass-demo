package com.flowglass.demo.xposed.a11y

import com.flowglass.demo.system.a11y.A11yKeepAlivePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yRepairWorkerTest {

    private class FakeOps(
        var decision: A11yKeepAlivePolicy.Result = A11yKeepAlivePolicy.Result(true, ""),
        var bindSucceeds: Boolean = true,
        var aliveAfterBind: Boolean = true,
    ) : A11yConnectionOps {
        var binds = 0
        var repaired = 0
        override fun componentOf(connection: Any): String = "com.a/com.a.A"
        override fun isAlive(connection: Any): Boolean = aliveAfterBind
        override fun decide(component: String, connection: Any, attempts: Int) = decision
        override fun bind(connection: Any): Boolean {
            binds++
            return bindSucceeds
        }
        override fun onRepaired(component: String) { repaired++ }
    }

    private class Harness {
        val ops = FakeOps()
        val statuses = mutableListOf<Pair<String, String>>()
        var now = 0L
        private val queue = ArrayDeque<() -> Unit>()
        val worker: A11yRepairWorker = A11yRepairWorker(
            ops = ops,
            schedule = { _, task -> queue += task },
            now = { now },
            log = {},
            onStatus = { _, state, reason -> statuses += state to reason },
            maxAttempts = 3,
            attemptWindowMs = 600_000L,
            backoffMs = longArrayOf(0L, 0L, 0L),
            verifyDelayMs = 0L,
        )

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }
    }

    @Test
    fun `rebinds once and reports repaired`() {
        val h = Harness()
        h.worker.onDisconnected("com.a/com.a.A", Any())
        h.drain()
        assertEquals(1, h.ops.binds)
        assertEquals(1, h.ops.repaired)
        assertEquals("repaired", h.statuses.last().first)
    }

    @Test
    fun `stops at three attempts`() {
        val h = Harness()
        h.ops.aliveAfterBind = false
        h.worker.onDisconnected("com.a/com.a.A", Any())
        h.drain()
        assertEquals(3, h.ops.binds)
        assertEquals("limit", h.statuses.last().first)
    }

    @Test
    fun `policy skip never binds`() {
        val h = Harness()
        h.ops.decision = A11yKeepAlivePolicy.Result(false, A11yKeepAlivePolicy.REASON_MASTER_OFF)
        h.worker.onDisconnected("com.a/com.a.A", Any())
        h.drain()
        assertEquals(0, h.ops.binds)
        assertEquals("skipped", h.statuses.last().first)
        assertEquals(A11yKeepAlivePolicy.REASON_MASTER_OFF, h.statuses.last().second)
    }

    @Test
    fun `duplicate disconnect while an attempt is pending is ignored`() {
        val h = Harness()
        h.worker.onDisconnected("com.a/com.a.A", Any())
        h.worker.onDisconnected("com.a/com.a.A", Any())
        h.drain()
        assertEquals(1, h.ops.binds)
        assertTrue(h.ops.repaired == 1)
    }
}
