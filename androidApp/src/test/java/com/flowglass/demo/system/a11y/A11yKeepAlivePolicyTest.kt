package com.flowglass.demo.system.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yKeepAlivePolicyTest {

    private fun decide(
        isTarget: Boolean = true,
        enabled: Boolean = true,
        master: Boolean = true,
        componentEnabled: Boolean = true,
        stopped: Boolean = false,
        bound: Boolean = false,
        attempts: Int = 0,
    ) = A11yKeepAlivePolicy.decide(
        isTarget = isTarget,
        keepAliveEnabled = enabled,
        a11yMasterEnabled = master,
        componentEnabled = componentEnabled,
        appStopped = stopped,
        alreadyBound = bound,
        attempts = attempts,
    )

    @Test
    fun `only a target is rebound`() {
        assertFalse(decide(isTarget = false).rebind)
        assertEquals(A11yKeepAlivePolicy.REASON_NOT_TARGET, decide(isTarget = false).reason)
        assertTrue(decide().rebind)
    }

    @Test
    fun `user intent always wins`() {
        assertEquals(A11yKeepAlivePolicy.REASON_DISABLED, decide(enabled = false).reason)
        assertEquals(A11yKeepAlivePolicy.REASON_MASTER_OFF, decide(master = false).reason)
        assertEquals(A11yKeepAlivePolicy.REASON_COMPONENT_REMOVED, decide(componentEnabled = false).reason)
        assertEquals(A11yKeepAlivePolicy.REASON_APP_STOPPED, decide(stopped = true).reason)
    }

    @Test
    fun `never double bind`() {
        assertEquals(A11yKeepAlivePolicy.REASON_ALREADY_BOUND, decide(bound = true).reason)
    }

    @Test
    fun `attempt cap stops the loop`() {
        assertTrue(decide(attempts = 2).rebind)
        assertEquals(A11yKeepAlivePolicy.REASON_LIMIT, decide(attempts = 3).reason)
    }
}
