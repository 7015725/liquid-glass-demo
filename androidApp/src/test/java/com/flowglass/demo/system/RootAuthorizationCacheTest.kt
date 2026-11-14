package com.flowglass.demo.system

import com.flowglass.demo.system.exec.ProcessResult
import com.flowglass.demo.system.exec.RootPermissionDeniedException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAuthorizationCacheTest {
    private class FakeStore(var value: RootInfo? = null) : RootGrantStore {
        var saves = 0
        var clears = 0
        override fun load(): RootInfo? = value
        override fun save(info: RootInfo) { value = info; saves++ }
        override fun clear() { value = null; clears++ }
    }

    private fun grantedInfo() =
        RootInfo(suFound = true, granted = true, uidLine = "uid=0(root)", manager = "Magisk 26.4", suPath = "/fake/su")

    @Test fun cachedGrantAvoidsAnotherProbe() = runTest {
        var idCalls = 0
        val root = RootAccess({ "/fake/su" }) { args, _ ->
            if (args.last() == "-v") ProcessResult(0, "MAGISK:fixture", false)
            else { idCalls++; ProcessResult(0, "uid=0(root)", false) }
        }

        assertTrue(root.check(request = true).granted)
        assertTrue(root.check(request = false).granted)
        assertEquals(1, idCalls)
    }

    @Test fun explicitRefreshRerunsProbeAndClearsRevokedGrant() = runTest {
        var granted = true
        var idCalls = 0
        val root = RootAccess({ "/fake/su" }) { args, _ ->
            if (args.last() == "-v") ProcessResult(0, "MAGISK:fixture", false)
            else { idCalls++; ProcessResult(0, if (granted) "uid=0(root)" else "uid=1000(app)", false) }
        }

        assertTrue(root.check(request = true).granted)
        granted = false
        assertFalse(root.check(request = true, refresh = true).granted)
        assertFalse(root.check(request = false).granted)
        assertEquals(2, idCalls)
    }

    @Test fun onlyASuccessfulGrantIsCachedAcrossConcurrentRequests() = runTest {
        var idCalls = 0
        val root = RootAccess({ "/fake/su" }) { args, _ ->
            if (args.last() == "-v") ProcessResult(0, "MAGISK:fixture", false)
            else { idCalls++; ProcessResult(0, "uid=0(root)", false) }
        }

        val results = (1..8).map { async { root.check(request = true) } }.awaitAll()

        assertTrue(results.all { it.granted })
        assertEquals(1, idCalls)
    }

    @Test fun transportDenialInvalidatesGrantWithoutReplayingCommand() = runTest {
        var idCalls = 0
        var commandCalls = 0
        var granted = true
        val root = RootAccess({ "/fake/su" }) { args, _ ->
            when (args.last()) {
                "-v" -> ProcessResult(0, "MAGISK:fixture", false)
                "id" -> {
                    idCalls++
                    ProcessResult(0, if (granted) "uid=0(root)" else "uid=1000(app)", false)
                }
                else -> {
                    commandCalls++
                    throw RootPermissionDeniedException()
                }
            }
        }

        assertTrue(root.check(request = true).granted)
        try {
            root.runProcess(listOf("/fake/su", "-c", "side-effect"))
            throw AssertionError("transport rejection must be reported")
        } catch (_: RootPermissionDeniedException) {
            // The rejected command is never retried because it may have side effects.
        }
        granted = false

        assertFalse(root.check(request = false).granted)
        assertFalse(root.check(request = true).granted)
        assertEquals(1, commandCalls)
        assertEquals(2, idCalls)
    }

    @Test fun normalCommandFailureAndTimeoutKeepTheCachedGrant() = runTest {
        var idCalls = 0
        var commandCalls = 0
        val root = RootAccess({ "/fake/su" }) { args, _ ->
            when (args.last()) {
                "-v" -> ProcessResult(0, "MAGISK:fixture", false)
                "id" -> { idCalls++; ProcessResult(0, "uid=0(root)", false) }
                "ordinary-failure" -> { commandCalls++; ProcessResult(7, "命令失败", false) }
                else -> { commandCalls++; ProcessResult(-1, "", true) }
            }
        }

        assertTrue(root.check(request = true).granted)
        root.runProcess(listOf("/fake/su", "-c", "ordinary-failure"))
        root.runProcess(listOf("/fake/su", "-c", "timeout"))

        assertTrue(root.check(request = false).granted)
        assertEquals(2, commandCalls)
        assertEquals(1, idCalls)
    }

    @Test fun aGrantSurvivesProcessDeathWithoutAskingTheManagerAgain() = runTest {
        val store = FakeStore()
        var idCalls = 0
        val executor: suspend (List<String>, Long) -> ProcessResult = { args, _ ->
            if (args.last() == "-v") ProcessResult(0, "MAGISK:26.4", false)
            else { idCalls++; ProcessResult(0, "uid=0(root)", false) }
        }

        assertTrue(RootAccess({ "/fake/su" }, executor).apply { this.store = store }.check(request = true).granted)
        assertEquals(1, idCalls)
        assertTrue("a successful grant must be persisted", store.value?.granted == true)

        // New process: nothing in memory, only the persisted copy.
        val restarted = RootAccess({ "/fake/su" }, executor).apply { this.store = store }
        val info = restarted.check(request = false)

        assertTrue("reopening the app must not lose the grant", info.granted)
        assertEquals("uid=0(root)", info.uidLine)
        assertTrue(info.manager.contains("MAGISK"))
        assertEquals("a passive check must not run su -c id", 1, idCalls)
    }

    @Test fun anExplicitRefreshClearsThePersistedGrantWhenRootWasRevoked() = runTest {
        val store = FakeStore(grantedInfo())
        var granted = false
        var idCalls = 0
        val root = RootAccess({ "/fake/su" }, { args, _ ->
            if (args.last() == "-v") ProcessResult(0, "MAGISK:26.4", false)
            else { idCalls++; ProcessResult(0, if (granted) "uid=0(root)" else "uid=1000(app)", false) }
        }).apply { this.store = store }

        assertTrue(root.check(request = false).granted)
        assertEquals(0, idCalls)

        assertFalse(root.check(request = true, refresh = true).granted)
        assertNull("revocation must drop the persisted grant", store.value)

        assertFalse(root.check(request = false).granted)
    }

    @Test fun aDeniedExecutionDropsThePersistedGrant() = runTest {
        val store = FakeStore(grantedInfo())
        val root = RootAccess({ "/fake/su" }, { args, _ ->
            when (args.last()) {
                "-v" -> ProcessResult(0, "MAGISK:26.4", false)
                "id" -> ProcessResult(0, "uid=0(root)", false)
                else -> throw RootPermissionDeniedException()
            }
        }).apply { this.store = store }

        assertTrue(root.check(request = false).granted)
        try {
            root.runProcess(listOf("/fake/su", "-c", "side-effect"))
            throw AssertionError("denial must be reported")
        } catch (_: RootPermissionDeniedException) {
        }

        assertNull("an execution denial must drop the persisted grant", store.value)
        assertFalse(root.check(request = false).granted)
    }

    @Test fun aPassiveCheckWithoutAPersistedGrantStillNeverPrompts() = runTest {
        var idCalls = 0
        val root = RootAccess({ "/fake/su" }, { args, _ ->
            if (args.last() == "-v") ProcessResult(0, "MAGISK:26.4", false)
            else { idCalls++; ProcessResult(0, "uid=0(root)", false) }
        }).apply { store = FakeStore() }

        val info = root.check(request = false)

        assertFalse("not requesting must not claim a grant", info.granted)
        assertTrue(info.suFound)
        assertEquals(0, idCalls)
    }

    @Test fun aPersistedGrantIsDroppedWhenSuDisappeared() = runTest {
        val store = FakeStore(grantedInfo())
        val root = RootAccess({ null }, { _, _ -> ProcessResult(1, "", false) }).apply { this.store = store }

        val info = root.check(request = false)

        assertFalse("unrooting must clear the remembered grant", info.granted)
        assertFalse(info.suFound)
        assertNull(store.value)
    }
}
