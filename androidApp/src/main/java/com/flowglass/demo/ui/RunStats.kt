package com.flowglass.demo.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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

fun computeRunStats(
    logs: List<RunLog>,
    nowMs: Long,
    offsetMinutes: Int = TimeZone.getDefault().getOffset(nowMs) / 60_000,
    zoneId: ZoneId? = null,
): RunStats {
    // A fixed offset cannot represent DST: production passes ZoneId.systemDefault(). Tests that
    // build synthetic UTC stamps can keep using the offset-only overload.
    val zone = zoneId ?: ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
    val currentHourStart = nowMs - Math.floorMod(nowMs, HOUR_MS)

    val real = logs.filter { it.at > 0 && !it.skipped }
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val todayLogs = real.filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }
    val todaySuccess = todayLogs.count { it.ok }

    var windowCount = 0
    val buckets = IntArray(24)
    // The headline uses a true sliding 24 h window. The chart keeps 24 clock-hour buckets ending
    // with the current hour; at an hour boundary the two can differ by the partial hour.
    val slidingStart = nowMs - 24 * HOUR_MS
    val chartStart = currentHourStart - 23 * HOUR_MS
    for (log in real) {
        if (log.at in slidingStart..nowMs) windowCount += 1
        if (log.at < chartStart || log.at > nowMs) continue
        val index = ((log.at - chartStart) / HOUR_MS).toInt().coerceIn(0, 23)
        buckets[index] += 1
    }

    val timed = real.mapNotNull { log ->
        val ms = if (log.durationMs > 0) log.durationMs else log.steps.sumOf { it.durationMs }
        ms.takeIf { it > 0 }
    }

    return RunStats(
        todayCount = todayLogs.size,
        todaySuccess = todaySuccess,
        successPercent = if (todayLogs.isEmpty()) null else (todaySuccess * 100.0 / todayLogs.size).toInt(),
        averageMs = if (timed.isEmpty()) null else timed.sum() / timed.size,
        hourly = buckets.toList(),
        last24Count = windowCount,
    )
}
