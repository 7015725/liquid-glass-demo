package com.flowglass.demo.ui

import com.flowglass.demo.system.exec.StepResult
import com.flowglass.demo.system.exec.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Overview metrics must come from real run stamps, including day boundaries and failures. */
class RunStatsTest {
    private val offset = 480 // UTC+8

    /** 2026-11-15 10:30 local time. */
    private val now = offset * 60_000L + 15L * 86_400_000L + 10 * 3_600_000L + 30 * 60_000L

    private fun log(
        at: Long,
        ok: Boolean = true,
        skipped: Boolean = false,
        durationMs: Long = 0,
        steps: List<StepResult> = emptyList(),
    ) = RunLog(
        id = at, title = "t", reason = "手动执行", time = "10:30", ok = ok, ic = Ic.Bolt,
        skipped = skipped, at = at, durationMs = durationMs, steps = steps,
    )

    @Test
    fun emptyHistoryHasNoFakeNumbers() {
        val stats = computeRunStats(emptyList(), now, offset)
        assertEquals(0, stats.todayCount)
        assertNull(stats.successPercent)
        assertNull(stats.averageMs)
        assertEquals(24, stats.hourly.size)
        assertEquals(0, stats.last24Count)
        assertTrue(!stats.hasData)
    }

    @Test
    fun todayCountsAndFailureRateIgnoreSkipsAndYesterday() {
        val crossDay = now - 20 * 3_600_000L
        val logs = listOf(
            log(now - 3_600_000L, ok = true, durationMs = 100),
            log(now - 600_000L, ok = false, durationMs = 300),
            log(now - 120_000L, ok = true, durationMs = 200),
            // Skipped rows are not attempts, so they must not drag the success rate down.
            log(now - 60_000L, ok = false, skipped = true),
            // Before local midnight: belongs to yesterday even though it is inside the 24h curve.
            log(crossDay, ok = false, durationMs = 9_000),
            // Legacy rows without a stamp cannot be placed on a day.
            log(0, ok = false),
        )
        val stats = computeRunStats(logs, now, offset)
        assertEquals(3, stats.todayCount)
        assertEquals(2, stats.todaySuccess)
        assertEquals(66, stats.successPercent)
        // All real runs in the list, including the failed one 20 hours ago.
        assertEquals(2_400L, stats.averageMs)
        assertEquals(4, stats.last24Count)
    }

    @Test
    fun hourlyBucketsFollowLocalHoursAcrossMidnight() {
        val logs = listOf(
            log(now - 30 * 60_000L),                 // current hour
            log(now - 3 * 3_600_000L),              // three hours ago (07:30 local)
            log(now - 25 * 3_600_000L, ok = false), // outside the 24h window
        )
        val stats = computeRunStats(logs, now, offset)
        assertEquals(1, stats.hourly[23])
        assertEquals(1, stats.hourly[20])
        assertEquals(2, stats.last24Count)
        assertEquals(0, stats.hourly.sum() - stats.last24Count)
    }

    @Test
    fun durationFallsBackToStepSumWhenLogHasNone() {
        val steps = listOf(
            StepResult(1, "a", StepStatus.SUCCESS, "", 400),
            StepResult(2, "b", StepStatus.SUCCESS, "", 600),
        )
        val stats = computeRunStats(listOf(log(now, steps = steps)), now, offset)
        assertEquals(1_000L, stats.averageMs)
    }

    @Test
    fun last24UsesASlidingWindowInsteadOfTheTopOfTheHour() {
        // 10:05 local; a run at 10:35 yesterday is 23.5 hours ago and must count.
        val tenPast = now - 25 * 60_000L
        val logs = listOf(log(tenPast - 23 * 3_600_000L - 30 * 60_000L))

        val stats = computeRunStats(logs, tenPast, offset)

        assertEquals(0, stats.todayCount)
        assertEquals(1, stats.last24Count)
    }

    @Test
    fun dstFallBackKeepsLocalMidnightRunsInToday() {
        val zone = ZoneId.of("America/New_York")
        // 2026-11-01 12:00 EST after the fall-back; 00:30 EDT is the same local date.
        val noon = java.time.ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val midnight = java.time.ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, zone).toInstant().toEpochMilli()

        val stats = computeRunStats(listOf(log(midnight)), noon, zoneId = zone)

        assertEquals(1, stats.todayCount)
        assertEquals(1, stats.last24Count)
    }
}
