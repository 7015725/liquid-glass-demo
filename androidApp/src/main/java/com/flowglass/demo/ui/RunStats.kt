package com.flowglass.demo.ui

import java.util.TimeZone

/**
 * Real numbers for the overview card, computed from the run history instead of demo values.
 *
 * [at] is an epoch millisecond stamp written when the run finished; legacy rows (at = 0) are
 * ignored because the old "HH:mm" label cannot be placed on a calendar day reliably.
 */
data class RunStats(
    val todayCount: Int,
    val todaySuccess: Int,
    val successPercent: Int?,
    val averageMs: Long?,
    /** 24 buckets, oldest first; the last bucket is the current hour. */
    val hourly: List<Int>,
    val last24Count: Int,
) {
    val hasData: Boolean get() = todayCount > 0 || last24Count > 0
}

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

fun computeRunStats(
    logs: List<RunLog>,
    nowMs: Long,
    offsetMinutes: Int = TimeZone.getDefault().getOffset(nowMs) / 60_000,
): RunStats {
    val offsetMs = offsetMinutes * 60_000L
    val localNow = nowMs + offsetMs
    val todayStart = localNow - Math.floorMod(localNow, DAY_MS) - offsetMs
    val currentHourStart = nowMs - Math.floorMod(nowMs, HOUR_MS)
    val windowStart = currentHourStart - 23 * HOUR_MS

    val real = logs.filter { it.at > 0 && !it.skipped }
    val today = real.filter { it.at in todayStart until todayStart + DAY_MS }
    val todaySuccess = today.count { it.ok }

    var windowCount = 0
    val buckets = IntArray(24)
    for (log in real) {
        if (log.at < windowStart || log.at > nowMs) continue
        val index = ((log.at - windowStart) / HOUR_MS).toInt().coerceIn(0, 23)
        buckets[index] += 1
        windowCount += 1
    }

    val timed = real.mapNotNull { log ->
        val ms = if (log.durationMs > 0) log.durationMs else log.steps.sumOf { it.durationMs }
        ms.takeIf { it > 0 }
    }

    return RunStats(
        todayCount = today.size,
        todaySuccess = todaySuccess,
        successPercent = if (today.isEmpty()) null else (todaySuccess * 100.0 / today.size).toInt(),
        averageMs = if (timed.isEmpty()) null else timed.sum() / timed.size,
        hourly = buckets.toList(),
        last24Count = windowCount,
    )
}
