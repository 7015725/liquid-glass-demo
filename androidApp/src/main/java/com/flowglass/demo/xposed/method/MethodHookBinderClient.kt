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
import java.util.concurrent.Executors
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
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FlowGlassMethodHookSender").apply { isDaemon = true }
    }
    private val session = randomSession()
    private val seq = AtomicLong(0L)
    private val greeted = linkedSetOf<String>()
    private val pending = ArrayDeque<String>()
    private var binder: IMethodHookEvent? = null
    private var bindingStarted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            executor.execute {
                binder = service?.let { IMethodHookEvent.Stub.asInterface(it) }
                // 宿主 Service 重建后内存里的 session 表是空的：这里清掉 greeted，让下一次事件
                // 重新发 hello，否则该进程的所有事件都会因为 session 未知被拒。
                greeted.clear()
                flush()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            executor.execute { binder = null; greeted.clear() }
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
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(packet)
        ensureBound()
        flush()
    }

    private fun ensureBound() {
        if (bindingStarted) return
        bindingStarted = true
        runCatching {
            appContext.bindService(
                Intent().setComponent(ComponentName(MODULE_PACKAGE, SERVICE_CLASS)),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.onFailure {
            // The App may be unavailable in this user. Keep queued packets for a later retry.
            bindingStarted = false
        }
    }

    private fun flush() {
        val service = binder ?: return
        while (pending.isNotEmpty()) {
            val packet = pending.first()
            val sent = runCatching { service.send(packet) }.getOrDefault(false)
            if (!sent) return
            pending.removeFirst()
        }
    }

    private fun randomSession(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MODULE_PACKAGE = "com.flowglass.demo"
        const val SERVICE_CLASS = "com.flowglass.demo.xposed.method.MethodHookEventService"
        const val MAX_PENDING = 256
    }
}
