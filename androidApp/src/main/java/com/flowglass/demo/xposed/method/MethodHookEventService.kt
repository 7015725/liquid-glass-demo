package com.flowglass.demo.xposed.method

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.flowglass.engine.methodhook.MethodHookStatus
import com.flowglass.engine.methodhook.MethodHookWireEnvelope
import com.flowglass.engine.methodhook.MethodHookWireJson
import com.flowglass.engine.methodhook.MethodHookWirePacket
import java.io.File

/**
 * Exported on purpose: the target app must be able to bind without holding our signature
 * permission. The Binder entry validates the calling UID/user/package, the per-hook HMAC and the
 * session/sequence before accepting anything. There is no other exported method surface.
 */
class MethodHookEventService : Service() {
    private val secrets by lazy { MethodHookSecrets(this) }
    private val guard = MethodHookReplayGuard()
    /**
     * 按调用方 UID 的粗粒度入口预算：在解析包体、读配置之前生效，未知 hookId 也计入。
     * 单个目标应用最多 8 个进程是文档支持的范围，这里给到 8 × 300 的余量。
     */
    private val uidLimiter = MethodHookIngressLimiter(defaultBudgetPerMinute = 2_400)
    private val runtimeSpecs = RuntimeSpecCache(load = { MethodHookConfigPublisher.readRuntime(this) })
    private val verifier by lazy {
        MethodHookEventVerifier(
            specFor = { hookId -> runtimeSpecs.spec(hookId) },
            tokenFor = { hookId -> secrets.token(hookId) },
            onEvent = { hookId, variables ->
                MethodHookEventSink.publish(hookId, variables)
            },
            onStatus = { report ->
                MethodHookStatusRegistry.update(
                    MethodHookStatusRegistry.Entry(
                        hookId = report.hookId,
                        packageName = report.packageName,
                        userId = report.userId,
                        processName = report.processName,
                        status = report.status,
                        detail = report.detail,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            },
        )
    }

    private val binder = object : IMethodHookEvent.Stub() {
        override fun send(packet: String?): Boolean {
            if (packet == null || packet.length > MAX_PACKET_CHARS) return true
            return try {
                val callingUid = Binder.getCallingUid()
                // 预算在解析/配置解码之前扣：超量包直接出队，不再消耗宿主 CPU。
                if (!uidLimiter.tryAcquire("uid:$callingUid")) return true
                val caller = MethodHookCaller(
                    uid = callingUid,
                    userId = MethodHookUser.idOfUid(callingUid),
                    packages = packageManager.getPackagesForUid(callingUid)?.toSet().orEmpty(),
                )
                val result = verifier.verify(caller, packet, guard)
                if (result is MethodHookVerification.Rejected) {
                    Log.w(TAG, "rejected method-hook packet: ${result.reason.take(120)}")
                }
                // 永久性拒绝（含入口限流）返回 true，让目标端把包出队；返回 false 会被当成
                // 传输失败无限重试，队首过期后还会卡住整个队列。
                result is MethodHookVerification.Accepted ||
                    (result is MethodHookVerification.Rejected && !result.retryable)
            } catch (failure: Throwable) {
                Log.w(TAG, "method-hook packet failed: ${failure.javaClass.simpleName}")
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The App process can be started by this bind before the UI exists. Publish the current
        // hooks so a target app that starts later reads a consistent token/spec pair.
        runCatching { MethodHookConfigPublisher.publish(this) }
        runtimeSpecs.invalidate()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "FlowGlass/MethodHook"
        const val MAX_PACKET_CHARS = 64 * 1024
    }
}

/** PR-5 replaces this no-op with the trigger event bus subscription. */
object MethodHookEventSink {
    @Volatile
    var listener: ((String, Map<String, String>) -> Unit)? = null

    fun publish(hookId: String, variables: Map<String, String>) {
        runCatching { listener?.invoke(hookId, variables) }
    }
}

internal sealed interface MethodHookVerification {
    data object Accepted : MethodHookVerification
        /** [retryable] 为 false 时目标端应直接把包出队，而不是保留队首无限重试。 */
    data class Rejected(val reason: String, val retryable: Boolean = false) : MethodHookVerification
}

internal class MethodHookEventVerifier(
    private val specFor: (String) -> com.flowglass.engine.methodhook.MethodHookSpec?,
    private val tokenFor: (String) -> String?,
    private val onEvent: (String, Map<String, String>) -> Unit,
    private val onStatus: (StatusReport) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ingress: MethodHookIngressLimiter = MethodHookIngressLimiter(),
) {
    data class StatusReport(
        val hookId: String,
        val packageName: String,
        val userId: Int,
        val processName: String,
        val status: String,
        val detail: String?,
    )

    fun verify(
        caller: MethodHookCaller,
        packetText: String,
        guard: MethodHookReplayGuard,
    ): MethodHookVerification {
        val packet = runCatching { MethodHookWireJson.decodePacket(packetText) }
            .getOrElse { return MethodHookVerification.Rejected("packet 不是合法 JSON") }
        if (packet.body.length > MAX_BODY_CHARS || packet.body.isEmpty()) {
            return MethodHookVerification.Rejected("body 过大或为空")
        }
        val spec = specFor(packet.hookId)
            ?: return MethodHookVerification.Rejected("未知应用方法调用")
        // 调用方校验是廉价的，必须先做：否则任意外部应用都能用猜到的 hookId 消耗目标应用
        // 的配额，把合法事件挤掉。
        if (!MethodHookCallerValidator.matches(caller, spec)) {
            return MethodHookVerification.Rejected("调用方 UID/user/包名不匹配")
        }
        // 预算按「spec 上限 × 8 个进程」给足，避免多进程聚合被误伤；UID 级桶另有总量上限。
        if (!ingress.tryAcquire(spec.id, spec.maxCallsPerMinute * 8)) {
            return MethodHookVerification.Rejected("入口限流：该钩子超出每分钟预算", retryable = false)
        }
        val token = tokenFor(spec.id)
            ?: return MethodHookVerification.Rejected("没有应用方法调用 token")
        if (!MethodHookHmac.verify(token, packet.body, packet.mac)) {
            return MethodHookVerification.Rejected("HMAC 校验失败")
        }
        val envelope = runCatching { MethodHookWireJson.decodeEnvelope(packet.body) }
            .getOrElse { return MethodHookVerification.Rejected("body 不是合法 JSON") }
        if (envelope.hookId != spec.id) {
            return MethodHookVerification.Rejected("body 调用标识不匹配")
        }
        if (envelope.packageName != spec.packageName || envelope.userId != spec.userId) {
            return MethodHookVerification.Rejected("包名或用户与配置不匹配")
        }
        if (!isValidProcessName(envelope.processName, spec.packageName)) {
            return MethodHookVerification.Rejected("进程名不匹配")
        }
        val key = MethodHookReplayGuard.Key(
            hookId = spec.id,
            packageName = spec.packageName,
            userId = spec.userId,
            processName = envelope.processName,
        )
        when (envelope.type) {
            MethodHookWireEnvelope.TYPE_HELLO -> {
                if (!guard.hello(key, envelope.session, envelope.timestamp)) {
                    return MethodHookVerification.Rejected("hello 时间戳或会话无效")
                }
                return MethodHookVerification.Accepted
            }
            MethodHookWireEnvelope.TYPE_INSTALL, TYPE_RATE -> {
                if (!guard.accept(key, envelope.session, envelope.seq, envelope.timestamp)) {
                    return MethodHookVerification.Rejected("安装状态 seq/session 无效")
                }
                val status = envelope.status
                    ?.takeIf { it in allowedStatuses }
                    ?: return MethodHookVerification.Rejected("未知安装状态")
                onStatus(
                    StatusReport(
                        hookId = spec.id,
                        packageName = spec.packageName,
                        userId = spec.userId,
                        processName = envelope.processName,
                        status = status,
                        detail = envelope.detail?.take(MAX_DETAIL_CHARS),
                    )
                )
                return MethodHookVerification.Accepted
            }
            MethodHookWireEnvelope.TYPE_EVENT -> {
                if (!guard.accept(key, envelope.session, envelope.seq, envelope.timestamp)) {
                    return MethodHookVerification.Rejected("事件 seq/session 无效")
                }
                if (envelope.variables.size > MAX_VARIABLES) {
                    return MethodHookVerification.Rejected("变量过多")
                }
                val expected = buildSet {
                    addAll(com.flowglass.engine.methodhook.MethodHookVariables.reserved)
                    addAll(spec.extractions.map { it.name })
                }
                if (envelope.variables.keys.any { it !in expected }) {
                    return MethodHookVerification.Rejected("包含未声明的变量")
                }
                if (envelope.variables.values.any { it.length > MAX_VALUE_CHARS }) {
                    return MethodHookVerification.Rejected("变量值过长")
                }
                onEvent(spec.id, envelope.variables)
                return MethodHookVerification.Accepted
            }
            else -> return MethodHookVerification.Rejected("未知包类型")
        }
    }

    private fun isValidProcessName(processName: String, packageName: String): Boolean =
        processName == packageName || processName.startsWith("$packageName:")

    private companion object {
        const val MAX_BODY_CHARS = 48 * 1024
        const val MAX_VARIABLES = 40
        const val MAX_VALUE_CHARS = 512
        const val MAX_DETAIL_CHARS = 300
        const val TYPE_RATE = "rate"
        val allowedStatuses = setOf(
            MethodHookStatus.INSTALLED,
            MethodHookStatus.NOT_FOUND,
            MethodHookStatus.AMBIGUOUS,
            MethodHookStatus.FAILED,
            MethodHookStatus.BLOCKED,
            MethodHookStatus.RATE_LIMITED,
        )
    }
}
