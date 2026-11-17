package com.flowglass.demo.xposed.method

import com.flowglass.engine.methodhook.MethodHookRuntimeJson
import com.flowglass.engine.methodhook.MethodHookSpec
import com.flowglass.engine.methodhook.MethodHookStatus
import com.flowglass.engine.methodhook.MethodHookWireEnvelope
import com.flowglass.engine.methodhook.MethodHookWireJson
import com.flowglass.engine.methodhook.MethodHookWirePacket
import org.junit.Assert.assertEquals
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
        paramCount = 1,
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
            packet(MethodHookWireEnvelope.TYPE_EVENT, 1, variables = mapOf("钩子应用" to spec.packageName)),
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
}
