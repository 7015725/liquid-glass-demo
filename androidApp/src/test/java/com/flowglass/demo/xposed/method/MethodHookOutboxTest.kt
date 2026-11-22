package com.flowglass.demo.xposed.method

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOOKS-003/004/005：绑定失败要能重试、队首不能被一条失败的包永久占住、hello 被丢弃后要重新武装。
 */
class MethodHookOutboxTest {
    private var now = 1_000L

    @Test
    fun aFailedBindIsRetriedOnTheNextPacket() {
        var attempts = 0
        val binding = MethodHookBinding {
            attempts++
            attempts >= 3
        }
        assertFalse(binding.ensure())
        assertFalse(binding.ensure())
        assertTrue(binding.ensure())
        assertEquals(3, attempts)
        // 已绑定后不再重复调用 bindService。
        assertTrue(binding.ensure())
        assertEquals(3, attempts)
    }

    @Test
    fun aStuckPacketIsDroppedAfterTheTtlSoOtherHooksKeepFlowing() {
        val sent = mutableListOf<String>()
        var failNext = true
        val outbox = MethodHookOutbox(
            send = { packet ->
                if (packet == "stuck" && failNext) false
                else { sent += packet; true }
            },
            clock = { now },
        )
        val droppedHello = mutableListOf<String>()
        val helloOutbox = MethodHookOutbox(
            send = { false },
            onDropHello = { droppedHello += it },
            clock = { now },
        )

        outbox.add("stuck", "call-1-1", hello = false)
        outbox.add("later", "call-1-2", hello = false)
        outbox.drain()
        assertTrue("失败的包暂时保留在队首", outbox.size == 2)

        // 31 秒后新事件入队：两条旧包都已过期，队首不会继续堵着，新包必须发出去。
        now += 31_000
        failNext = false
        outbox.add("fresh", "call-1-3", hello = false)
        outbox.drain()
        assertEquals(listOf("fresh"), sent)
        assertEquals("过期的包必须被丢弃", 0, outbox.size)

        helloOutbox.add("hello", "call-1-1", hello = true)
        now += 31_000
        helloOutbox.drain()
        assertEquals(listOf("call-1-1"), droppedHello)
    }

    @Test
    fun theQueueIsBoundedEvenWhileNothingCanBeSent() {
        val outbox = MethodHookOutbox(send = { false }, clock = { now }, maxPending = 4)
        repeat(10) { index -> outbox.add("packet-$index", "hook", hello = false) }
        assertEquals(4, outbox.size)
    }
}
