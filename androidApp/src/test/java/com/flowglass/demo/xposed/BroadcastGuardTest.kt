package com.flowglass.demo.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastGuardTest {
    @Test
    fun sameStateIsIgnoredUntilContentChanges() {
        val gate = ContentGate()
        assertTrue(gate.allow("enabled=true|1||true"))
        repeat(10_000) {
            assertFalse(gate.allow("enabled=true|1||true"))
        }
        assertTrue(gate.allow("enabled=false|1||true"))
        assertTrue(gate.allow("enabled=true|1||true"))
    }

    @Test
    fun messageRateIsBoundedPerContentAndRecoversAfterTheInterval() {
        val limiter = BroadcastRateLimiter(minIntervalMs = 2_000L)
        assertTrue(limiter.allow("STATE|enabled=true", 0L))
        assertFalse(limiter.allow("STATE|enabled=true", 1_999L))
        assertTrue(limiter.allow("STATE|enabled=true", 2_000L))
        assertTrue(limiter.allow("CONFIG|families=1", 2_000L))
    }

    /**
     * Simulates the exact pre-fix chain: hook receives STATE/CONFIG -> starts heartbeat ->
     * app receives heartbeat -> app refreshes config -> hook receives CONFIG. Both gates must
     * keep the loop finite even when the app keeps replying.
     */
    @Test
    fun simulatedHandshakeLoopHasABoundedMessageCount() {
        val hookState = ContentGate()
        val hookLoaded = BroadcastRateLimiter(2_000L)
        val appState = BroadcastRateLimiter(2_000L)
        val appConfig = BroadcastRateLimiter(2_000L)
        var heartbeatRunning = false
        var appSends = 0
        var hookReceives = 0
        lateinit var hook: () -> Unit
        val appOnLoaded: () -> Unit = {
            if (appState.allow("STATE|enabled=true|loaded=true", 0L)) {
                appSends++
                hook()
            }
        }
        val appOnHeartbeat: () -> Unit = {
            if (appConfig.allow("CONFIG|families=1|watched=", 0L)) {
                appSends++
                hook()
            }
        }
        hook = {
            hookReceives++
            if (hookState.allow("enabled=true|1||true")) {
                if (!heartbeatRunning) {
                    heartbeatRunning = true
                    appOnHeartbeat()
                }
                appOnLoaded()
            }
        }

        // One real handshake plus 10,000 malicious/duplicated app send attempts at the same
        // instant. The app limiter must reject every repeat, so the recursive loop stops.
        hook()
        repeat(10_000) {
            appOnLoaded()
            appOnHeartbeat()
        }

        assertTrue("app must not send a storm, was $appSends", appSends <= 4)
        assertTrue("hook loop must stay bounded, was $hookReceives", hookReceives <= 5)
    }
}
