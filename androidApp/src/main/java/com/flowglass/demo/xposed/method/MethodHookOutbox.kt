package com.flowglass.demo.xposed.method

/**
 * 目标进程侧的发送队列。抽出来是为了可测：队首卡住、hello 丢失、绑定失败重试这些行为
 * 都在普通 JVM 用例里覆盖，不需要 Robolectric 或真实 Binder。
 *
 * 关键点：一条包最多在队首等 [ttlMs]（默认 30 秒），超时就丢弃并让调用方重新武装 hello；
 * 否则一个永久失败的包会把整个进程（所有钩子共用一个队列）堵死。
 */
internal class MethodHookOutbox(
    private val send: (String) -> Boolean,
    private val onDropHello: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 30_000,
    private val maxPending: Int = 256,
) {
    private class Entry(val packet: String, val hookId: String, val hello: Boolean, val at: Long)

    private val queue = ArrayDeque<Entry>()

    fun add(packet: String, hookId: String, hello: Boolean) {
        if (queue.size >= maxPending) {
            // 上限淘汰也要维持「hello 丢了就重新武装」这条不变量，否则 greeted 会一直以为
            // hello 已经发过，这个钩子再也不补 hello。
            val evicted = queue.removeFirst()
            if (evicted.hello) onDropHello(evicted.hookId)
        }
        queue.addLast(Entry(packet, hookId, hello, clock()))
    }

    fun drain() {
        while (true) {
            val head = queue.firstOrNull() ?: return
            if (clock() - head.at > ttlMs) {
                queue.removeFirst()
                if (head.hello) onDropHello(head.hookId)
                continue
            }
            if (!send(head.packet)) return
            queue.removeFirst()
        }
    }

    val size: Int get() = queue.size
}

/**
 * bindService 可能返回 false 而不抛异常；此前把它当成「已绑定」，之后再也不重试，
 * 目标应用在本用户内就永远连不上宿主。
 */
internal class MethodHookBinding(private val bind: () -> Boolean) {
    private var bound = false

    /** 返回是否已绑定；失败时保持未绑定，下一次入队会再次尝试。 */
    fun ensure(): Boolean {
        if (bound) return true
        bound = runCatching(bind).getOrDefault(false)
        return bound
    }

    fun reset() {
        bound = false
    }
}
