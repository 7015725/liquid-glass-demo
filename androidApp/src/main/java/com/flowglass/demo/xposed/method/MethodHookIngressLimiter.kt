package com.flowglass.demo.xposed.method

import android.os.SystemClock

/**
 * 宿主侧的入口预算，两道：
 * - UID 级粗粒度桶在解析包体、读配置之前扣，未知 hookId 也计入，挡住外部应用灌包；
 * - 钩子级桶在调用方校验之后扣，预算按 spec 的每分钟上限 × 允许进程数放大，避免多进程合法流量被截断。
 */
internal class MethodHookIngressLimiter(
    private val defaultBudgetPerMinute: Int = 480,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val buckets = HashMap<String, MethodHookRateLimiter>()

    @Synchronized
    fun tryAcquire(key: String, budgetPerMinute: Int = defaultBudgetPerMinute): Boolean {
        val perMinute = budgetPerMinute.coerceIn(1, 20_000)
        val bucket = buckets.getOrPut(key) {
            // 突发额度按每分钟额度的 1/4 给（至少 30，且不超过每分钟上限），保证短时突发不被误伤。
            MethodHookRateLimiter(
                perMinute,
                burst = minOf(perMinute, maxOf(30, perMinute / 4)),
                clock = clock,
            )
        }
        return bucket.tryAcquire()
    }

    /** 被限流丢弃的包总数，用于状态页或日志。 */
    @Synchronized
    fun suppressed(): Long = buckets.values.sumOf { it.suppressed() }
}

/**
 * 运行时配置解码缓存：`readRuntime` 每次都会重读 SharedPreferences 并解码整份 JSON，
 * 入口处不能每个包都做一遍。按 2 秒 TTL 刷新，发布动作可以调用 [invalidate] 立即失效。
 */
internal class RuntimeSpecCache(
    private val load: () -> com.flowglass.engine.methodhook.MethodHookRuntimeConfig?,
    private val ttlMs: Long = 2_000,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var loadedAt = Long.MIN_VALUE
    private var config: com.flowglass.engine.methodhook.MethodHookRuntimeConfig? = null

    @Synchronized
    fun spec(hookId: String): com.flowglass.engine.methodhook.MethodHookSpec? {
        val now = clock()
        if (loadedAt == Long.MIN_VALUE || now - loadedAt > ttlMs) {
            config = runCatching(load).getOrNull()
            loadedAt = now
        }
        return config?.hooks?.firstOrNull { it.spec.id == hookId }?.spec
    }

    @Synchronized
    fun invalidate() {
        loadedAt = Long.MIN_VALUE
        config = null
    }
}
