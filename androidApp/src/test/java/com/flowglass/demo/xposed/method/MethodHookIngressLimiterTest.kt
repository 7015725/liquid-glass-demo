package com.flowglass.demo.xposed.method

import com.flowglass.engine.methodhook.MethodHookRuntimeConfig
import com.flowglass.engine.methodhook.MethodHookRuntimeHook
import com.flowglass.engine.methodhook.MethodHookSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 宿主入口预算必须能装下 UID 级 2400/分和钩子级 4800/分；此前复用目标侧桶（硬上限 600）会在
 * 构造时抛异常，让所有包都被当成传输失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MethodHookIngressLimiterTest {
    private var now = 1_000L

    @Test
    fun largeHostBudgetsAreConstructibleAndEnforced() {
        val limiter = MethodHookIngressLimiter(clock = { now })
        var accepted = 0
        repeat(3_000) { if (limiter.tryAcquire("uid:10001", 2_400)) accepted++ }
        assertEquals(600, accepted)

        var hookAccepted = 0
        repeat(5_000) { if (limiter.tryAcquire("hook", 4_800)) hookAccepted++ }
        assertEquals(1_200, hookAccepted)
        assertTrue(limiter.suppressed() > 0)
    }

    @Test
    fun aNewlyPublishedHookIsFoundWithoutWaitingForTheTtl() {
        val spec = MethodHookSpec(
            id = "call-1-2",
            packageName = "com.example.target",
            className = "com.example.Target",
            methodName = "run",
        )
        var published: List<MethodHookRuntimeHook> = emptyList()
        val cache = RuntimeSpecCache(
            load = { MethodHookRuntimeConfig(hooks = published) },
            ttlMs = 60_000,
            refreshGapMs = 1_000,
            clock = { now },
        )

        assertNull(cache.spec(spec.id))
        published = listOf(MethodHookRuntimeHook(spec, "token"))
        // 同一秒内不重复刷新，避免无效 hookId 拖着宿主反复解码整份配置。
        assertNull(cache.spec(spec.id))
        now += 1_001
        assertNotNull(cache.spec(spec.id))
        assertFalse(cache.spec("call-9-9") != null)
    }
}
