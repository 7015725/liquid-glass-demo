package com.flowglass.demo.system

import com.flowglass.demo.system.exec.ProcessResult
import com.flowglass.demo.system.exec.RootPermissionDeniedException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAuthorizationCacheTest {
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
}