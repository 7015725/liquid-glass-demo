package com.flowglass.demo.xposed.method

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ThreadPoolExecutor

/** HOOKS-006：发送器队列必须有界，宿主不可用时不能无限堆积。 */
class MethodHookSenderExecutorTest {
    @Test
    fun theSenderQueueIsBounded() {
        val executor = methodHookSenderExecutor()
        assertTrue(executor.queue is java.util.concurrent.ArrayBlockingQueue<*>)
        assertEquals(METHOD_HOOK_QUEUE_CAPACITY, executor.queue.remainingCapacity())
        val policy = executor.rejectedExecutionHandler
        assertTrue("队列满时必须丢最旧的任务而不是阻塞 hook 回调", policy is ThreadPoolExecutor.DiscardOldestPolicy)
        executor.shutdownNow()
    }
}
