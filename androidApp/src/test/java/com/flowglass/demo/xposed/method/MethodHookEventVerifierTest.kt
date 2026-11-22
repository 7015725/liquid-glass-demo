package com.flowglass.demo.xposed.method

import com.flowglass.engine.methodhook.MethodHookRuntimeJson
import com.flowglass.engine.methodhook.MethodHookSpec
import com.flowglass.engine.methodhook.MethodHookStatus
import com.flowglass.engine.methodhook.MethodHookVariables
import com.flowglass.engine.methodhook.MethodHookWireEnvelope
import com.flowglass.engine.methodhook.MethodHookWireJson
import com.flowglass.engine.methodhook.MethodHookWirePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MethodHookEventVerifierTest {
    private var now = 100_000L
    private val spec = MethodHookSpec(
        id = "h1",
        packageName = "com.example.target",
        userId = 0,
        className = "com.example.Target",
        methodName = "run",
    )
    private val token = "secret-token"
    private var events = 0
    private var statuses = 0
    private val verifier = MethodHookEventVerifier(
        specFor = { if (it == spec.id) spec else null },
        tokenFor = { if (it == spec.id) token else null },
        onEvent = { _, _ -> events++ },
        onStatus = { statuses++ },
        clock = { now },
    )
    private val caller = MethodHookCaller(10_001, 0, setOf("com.example.target"))
    private val guard = MethodHookReplayGuard { now }

    private fun packet(
        type: String,
        seq: Long,
        session: String = "session",
        timestamp: Long = now,
        variables: Map<String, String> = emptyMap(),
        status: String? = null,
        packetToken: String = token,
        packetBody: ((String) -> String)? = null,
    ): String {
        val envelope = MethodHookWireEnvelope(
            hookId = spec.id,
            packageName = spec.packageName,
            userId = spec.userId,
            processName = "com.example.target",
            session = session,
            seq = seq,
            timestamp = timestamp,
            type = type,
            variables = variables,
            status = status,
        )
        val body = MethodHookWireJson.encodeEnvelope(envelope)
        val signedBody = packetBody?.invoke(body) ?: body
        return MethodHookWireJson.encodePacket(
            MethodHookWirePacket(
                hookId = spec.id,
                body = signedBody,
                mac = MethodHookHmac.sign(packetToken, signedBody),
            )
        )
    }

    @Test
    fun helloThenSignedEventIsAcceptedAndStatusIsReported() {
        assertTrue(verifier.verify(caller, packet(MethodHookWireEnvelope.TYPE_HELLO, 0), guard)
            is MethodHookVerification.Accepted)
        assertTrue(verifier.verify(
            caller,
            packet(MethodHookWireEnvelope.TYPE_EVENT, 1, variables = mapOf(MethodHookVariables.APP to spec.packageName)),
            guard,
        ) is MethodHookVerification.Accepted)
        assertEquals(1, events)

        assertTrue(verifier.verify(
            caller,
            packet(MethodHookWireEnvelope.TYPE_INSTALL, 2, status = MethodHookStatus.INSTALLED),
            guard,
        ) is MethodHookVerification.Accepted)
        assertEquals(1, statuses)
    }

    @Test
    fun wrongCallerMacAndUnknownVariablesAreRejected() {
        assertTrue(verifier.verify(caller, packet(MethodHookWireEnvelope.TYPE_HELLO, 0), guard)
            is MethodHookVerification.Accepted)
        assertTrue(verifier.verify(
            MethodHookCaller(10_002, 0, setOf("com.example.other")),
            packet(MethodHookWireEnvelope.TYPE_EVENT, 1),
            guard,
        ) is MethodHookVerification.Rejected)
        assertTrue(verifier.verify(
            caller,
            packet(MethodHookWireEnvelope.TYPE_EVENT, 1, packetToken = "wrong"),
            guard,
        ) is MethodHookVerification.Rejected)
        assertTrue(verifier.verify(
            caller,
            packet(MethodHookWireEnvelope.TYPE_EVENT, 1, variables = mapOf("未声明" to "x")),
            guard,
        ) is MethodHookVerification.Rejected)
    }

    @Test
    fun replayAndOutOfOrderSequenceAreRejected() {
        assertTrue(verifier.verify(caller, packet(MethodHookWireEnvelope.TYPE_HELLO, 0), guard)
            is MethodHookVerification.Accepted)
        val first = packet(MethodHookWireEnvelope.TYPE_EVENT, 1)
        assertTrue(verifier.verify(caller, first, guard) is MethodHookVerification.Accepted)
        assertTrue(verifier.verify(caller, first, guard) is MethodHookVerification.Rejected)
        now += 1
        assertTrue(verifier.verify(caller, packet(MethodHookWireEnvelope.TYPE_EVENT, 1), guard)
            is MethodHookVerification.Rejected)
    }

    @Test
    fun runtimeConfigRoundTripsWithoutLosingTheToken() {
        val config = com.flowglass.engine.methodhook.MethodHookRuntimeConfig(
            hooks = listOf(com.flowglass.engine.methodhook.MethodHookRuntimeHook(spec, token)),
        )
        val decoded = MethodHookRuntimeJson.decode(MethodHookRuntimeJson.encode(config))
        assertEquals(token, decoded.hooks.single().token)
        assertEquals(spec, decoded.hooks.single().spec)
    }
    @Test
    fun aFloodingTargetIsStoppedByTheHostIngressLimit() {
        assertTrue(verifier.verify(caller, packet(MethodHookWireEnvelope.TYPE_HELLO, 0), guard)
            is MethodHookVerification.Accepted)
        var accepted = 0
        var limited = 0
        // 默认 spec 60/分 × 8 个进程 = 480/分，突发 120：hello 占 1 个后事件能过 119 个。
        for (seq in 1..200L) {
            when (val result = verifier.verify(
                caller,
                packet(MethodHookWireEnvelope.TYPE_EVENT, seq,
                    variables = mapOf(MethodHookVariables.APP to spec.packageName)),
                guard,
            )) {
                is MethodHookVerification.Accepted -> accepted++
                is MethodHookVerification.Rejected -> {
                    assertTrue("意外的拒绝原因：" + result.reason, result.reason.contains("限流"))
                    // 限流是终态：目标端必须把包出队，不能保留队首无限重试。
                    assertTrue("限流应标记为不可重试", !result.retryable)
                    limited++
                }
            }
        }
        // hello 也消耗一个令牌，所以 120 个突发额度里事件只拿到 119 个。
        assertEquals(119, accepted)
        assertEquals(81, limited)
        assertEquals(119, events)
    }
    @Test
    fun aWrongCallerCannotDrainTheBudgetOfTheRealTarget() {
        val intruder = MethodHookCaller(20_002, 0, setOf("com.example.other"))
        repeat(200) { seq ->
            assertTrue(
                verifier.verify(
                    intruder,
                    packet(MethodHookWireEnvelope.TYPE_EVENT, seq.toLong(),
                        variables = mapOf(MethodHookVariables.APP to spec.packageName)),
                    guard,
                ) is MethodHookVerification.Rejected
            )
        }
        assertTrue(verifier.verify(caller, packet(MethodHookWireEnvelope.TYPE_HELLO, 0), guard)
            is MethodHookVerification.Accepted)
        assertTrue(verifier.verify(
            caller,
            packet(MethodHookWireEnvelope.TYPE_EVENT, 1,
                variables = mapOf(MethodHookVariables.APP to spec.packageName)),
            guard,
        ) is MethodHookVerification.Accepted)
    }
    @Test
    fun anUnknownHookIdDropsEventsButLetsHelloRetry() {
        val event = MethodHookWireJson.encodePacket(
            MethodHookWirePacket(
                hookId = "call-9-9",
                body = MethodHookWireJson.encodeEnvelope(
                    MethodHookWireEnvelope(
                        hookId = "call-9-9",
                        packageName = spec.packageName,
                        userId = spec.userId,
                        processName = "com.example.target",
                        session = "session",
                        seq = 1,
                        timestamp = now,
                        type = MethodHookWireEnvelope.TYPE_EVENT,
                    )
                ),
                mac = "00",
            )
        )
        val eventResult = verifier.verify(caller, event, guard) as MethodHookVerification.Rejected
        assertFalse("未知 hook 的事件不该重试", eventResult.retryable)

        val hello = MethodHookWireJson.encodePacket(
            MethodHookWirePacket(
                hookId = "call-9-9",
                body = MethodHookWireJson.encodeEnvelope(
                    MethodHookWireEnvelope(
                        hookId = "call-9-9",
                        packageName = spec.packageName,
                        userId = spec.userId,
                        processName = "com.example.target",
                        session = "session",
                        seq = 0,
                        timestamp = now,
                        type = MethodHookWireEnvelope.TYPE_HELLO,
                    )
                ),
                mac = "00",
            )
        )
        val helloResult = verifier.verify(caller, hello, guard) as MethodHookVerification.Rejected
        assertTrue("未知 hook 的 hello 必须允许重试，否则进程永久失联", helloResult.retryable)
    }
}
