package com.flowglass.demo.xposed.method

import android.os.SystemClock

/**
 * 宿主侧的入口预算，两道：
 * - UID 级粗粒度桶在解析包体、读配置之前扣，未知 hookId 也计入，挡住外部应用灌包；
 * - 钩子级桶在调用方校验之后扣，预算按 spec 的每分钟上限 × 允许进程数放大，避免多进程合法流量被截断。
 *
 * 这里用自己的令牌桶而不是 [MethodHookRateLimiter]：后者是目标进程侧的实现，硬限制每分钟 600，
 * 拿来装宿主预算会直接抛 IllegalArgumentException。
 */
internal class MethodHookIngressLimiter(
    private val defaultBudgetPerMinute: Int = 480,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Bucket(var tokens: Double, val capacity: Double, val perMinute: Int, var lastMs: Long) {
        var suppressed = 0L
    }

    private val buckets = HashMap<String, Bucket>()

    @Synchronized
    fun tryAcquire(key: String, budgetPerMinute: Int = defaultBudgetPerMinute): Boolean {
        val perMinute = budgetPerMinute.coerceIn(1, 60_000)
        // 突发额度按每分钟额度的 1/4 给（至少 30，且不超过每分钟上限）。
        val capacity = minOf(perMinute, maxOf(30, perMinute / 4)).toDouble()
        val now = clock()
        val bucket = buckets.getOrPut(key) { Bucket(capacity, capacity, perMinute, now) }
        val elapsed = (now - bucket.lastMs).coerceAtLeast(0L)
        if (elapsed > 0) {
            bucket.tokens = (bucket.tokens + elapsed * bucket.perMinute / 60_000.0)
                .coerceAtMost(bucket.capacity)
            bucket.lastMs = now
        }
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return true
        }
        bucket.suppressed++
        return false
    }

    /** 被限流丢弃的包总数，用于状态页或日志。 */
    @Synchronized
    fun suppressed(): Long = buckets.values.sumOf { it.suppressed }
}

/**
 * 运行时配置解码缓存：`readRuntime` 每次都会重读 SharedPreferences 并解码整份 JSON，
 * 入口处不能每个包都做一遍。TTL 内直接复用；未知 hookId 触发一次受限的强制刷新，
 * 让刚发布的配置不用等 TTL 也能被认出来。
 */
internal class RuntimeSpecCache(
    private val load: () -> com.flowglass.engine.methodhook.MethodHookRuntimeConfig?,
    private val ttlMs: Long = 2_000,
    private val refreshGapMs: Long = 1_000,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var loadedAt = Long.MIN_VALUE
    private var refreshedAt = Long.MIN_VALUE
    private var config: com.flowglass.engine.methodhook.MethodHookRuntimeConfig? = null

    @Synchronized
    fun spec(hookId: String): com.flowglass.engine.methodhook.MethodHookSpec? {
        val now = clock()
        if (loadedAt == Long.MIN_VALUE || now - loadedAt > ttlMs) reload(now)
        config?.hooks?.firstOrNull { it.spec.id == hookId }?.spec?.let { return it }
        // 未命中：可能是刚发布、缓存还没过期。最多每秒强制刷新一次，避免被无效 hookId 拖着反复解码。
        if (refreshedAt == Long.MIN_VALUE || now - refreshedAt >= refreshGapMs) {
            reload(now)
            refreshedAt = now
            config?.hooks?.firstOrNull { it.spec.id == hookId }?.spec?.let { return it }
        }
        return null
    }

    @Synchronized
    fun invalidate() {
        loadedAt = Long.MIN_VALUE
        refreshedAt = Long.MIN_VALUE
        config = null
    }

    private fun reload(now: Long) {
        config = runCatching(load).getOrNull()
        loadedAt = now
    }
}
