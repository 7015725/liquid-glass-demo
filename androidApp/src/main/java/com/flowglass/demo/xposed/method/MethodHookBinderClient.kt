package com.flowglass.demo.xposed.method

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.flowglass.engine.methodhook.MethodHookSpec
import com.flowglass.engine.methodhook.MethodHookWireEnvelope
import com.flowglass.engine.methodhook.MethodHookWireJson
import com.flowglass.engine.methodhook.MethodHookWirePacket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Target-process side of the Binder channel. Packets are queued and sent off the hooked method's
 * thread; a connection failure never propagates into the target application. The App validates
 * the caller UID and the per-hook HMAC again, so a token leak in a shared-UID process is the only
 * accepted trust boundary.
 */
internal class MethodHookBinderClient(
    context: Context,
    private val packageName: String,
    private val userId: Int,
    private val processName: String,
    private val tokenFor: (String) -> String?,
) : MethodHookEmitter {
    private val appContext = context.applicationContext ?: context
    private val executor = methodHookSenderExecutor()
    private val session = randomSession()
    private val seq = AtomicLong(0L)
    private val greeted = linkedSetOf<String>()
    private var binder: IMethodHookEvent? = null
    private val outbox = MethodHookOutbox(
        send = { packet -> binder?.let { runCatching { it.send(packet) }.getOrDefault(false) } ?: false },
        // 队首 hello 超时被丢弃后要重新武装，否则这个进程再也不会发 hello。
        onDropHello = { hookId -> greeted.remove(hookId) },
    )
    // lazy 打破与 connection 的初始化环（connection 里会调用 binding.reset()）。
    private val binding: MethodHookBinding by lazy {
        MethodHookBinding {
            appContext.bindService(
                Intent().setComponent(ComponentName(MODULE_PACKAGE, SERVICE_CLASS)),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            executor.execute {
                binder = service?.let { IMethodHookEvent.Stub.asInterface(it) }
                // 宿主 Service 重建后内存里的 session 表是空的：这里清掉 greeted，让下一次事件
                // 重新发 hello，否则该进程的所有事件都会因为 session 未知被拒。
                greeted.clear()
                binding.reset()
                flush()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            executor.execute { binder = null; greeted.clear(); binding.reset() }
        }
    }

    override fun emit(
        spec: MethodHookSpec,
        type: String,
        variables: Map<String, String>,
        status: String?,
        detail: String?,
    ) {
        val token = tokenFor(spec.id) ?: return
        executor.execute {
            if (type != MethodHookWireEnvelope.TYPE_HELLO && greeted.add(spec.id)) {
                enqueue(spec, token, MethodHookWireEnvelope.TYPE_HELLO, emptyMap(), null, null)
            }
            enqueue(spec, token, type, variables, status, detail)
        }
    }

    private fun enqueue(
        spec: MethodHookSpec,
        token: String,
        type: String,
        variables: Map<String, String>,
        status: String?,
        detail: String?,
    ) {
        val now = System.currentTimeMillis()
        val envelope = MethodHookWireEnvelope(
            hookId = spec.id,
            packageName = packageName,
            userId = userId,
            processName = processName,
            session = session,
            seq = if (type == MethodHookWireEnvelope.TYPE_HELLO) 0L else seq.incrementAndGet(),
            timestamp = now,
            type = type,
            variables = variables,
            status = status,
            detail = detail?.take(300),
        )
        val body = MethodHookWireJson.encodeEnvelope(envelope)
        val mac = MethodHookHmac.sign(token, body)
        val packet = MethodHookWireJson.encodePacket(MethodHookWirePacket(spec.id, body, mac))
        outbox.add(packet, spec.id, type == MethodHookWireEnvelope.TYPE_HELLO)
        binding.ensure()
        outbox.drain()
    }

    private fun flush() {
        if (binder != null) outbox.drain()
    }

    private fun randomSession(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MODULE_PACKAGE = "com.flowglass.demo"
        const val SERVICE_CLASS = "com.flowglass.demo.xposed.method.MethodHookEventService"
    }
}

/**
 * 单线程发送器，但队列必须有界：hook 回调在目标应用的主线程/工作线程上，不能阻塞，
 * 所以队列满时丢最旧的待发送任务（下一次 emit 会重新入队 hello）。此前用的是无界的
 * newSingleThreadExecutor，宿主不可用时可以把目标进程内存吃光。
 */
internal fun methodHookSenderExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(METHOD_HOOK_QUEUE_CAPACITY),
    { runnable -> Thread(runnable, "FlowGlassMethodHookSender").apply { isDaemon = true } },
    ThreadPoolExecutor.DiscardOldestPolicy(),
)

internal const val METHOD_HOOK_QUEUE_CAPACITY = 256
