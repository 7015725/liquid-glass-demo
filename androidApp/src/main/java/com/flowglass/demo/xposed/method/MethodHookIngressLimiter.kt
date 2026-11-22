package com.flowglass.demo.xposed.method

import android.os.SystemClock

/**
 * 宿主侧的入口限流：每个 hookId 一个令牌桶，在解析 body 与校验 HMAC 之前就用掉一次配额。
 * 目标进程侧的 [MethodHookRateLimiter] 只保护目标应用自己；已授权的目标仍可能被篡改或
 * 被洪泛，这里保证宿主的工作量有上限。
 */
internal class MethodHookIngressLimiter(
    private val maxPerMinute: Int = 240,
    private val burst: Int = 60,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val buckets = HashMap<String, MethodHookRateLimiter>()

    @Synchronized
    fun tryAcquire(hookId: String): Boolean =
        buckets.getOrPut(hookId) { MethodHookRateLimiter(maxPerMinute, burst, clock) }.tryAcquire()

    /** 被限流丢弃的包总数，用于状态页或日志。 */
    @Synchronized
    fun suppressed(): Long = buckets.values.sumOf { it.suppressed() }
}
