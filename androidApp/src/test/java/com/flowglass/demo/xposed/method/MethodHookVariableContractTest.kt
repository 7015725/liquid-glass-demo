package com.flowglass.demo.xposed.method

import com.flowglass.engine.methodhook.MethodExtraction
import com.flowglass.engine.methodhook.MethodExtractionSource
import com.flowglass.engine.methodhook.MethodHookSpec
import com.flowglass.engine.methodhook.MethodHookVariables
import com.flowglass.engine.methodhook.MethodHookWireEnvelope
import com.flowglass.engine.methodhook.MethodHookWireJson
import com.flowglass.engine.methodhook.MethodHookWirePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HOOKS-007/008：接收端变量上限必须按「元数据 + 声明的提取项」计算，变量名比较必须与发送侧
 * 用同一套 trim 归一化，否则合法事件会被判成「变量过多」或「包含未声明的变量」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MethodHookVariableContractTest {
    private var now = 100_000L
    private var received: Map<String, String> = emptyMap()

    private fun specWith(extractions: List<MethodExtraction>) = MethodHookSpec(
        id = "h-vars",
        packageName = "com.example.target",
        userId = 0,
        className = "com.example.Target",
        methodName = "run",
        extractions = extractions,
    )

    private fun verifierFor(spec: MethodHookSpec) = MethodHookEventVerifier(
        specFor = { if (it == spec.id) spec else null },
        tokenFor = { "token" },
        onEvent = { _, vars -> received = vars },
        onStatus = {},
        clock = { now },
    )

    private fun hello(spec: MethodHookSpec): String {
        val envelope = MethodHookWireEnvelope(
            hookId = spec.id,
            packageName = spec.packageName,
            userId = spec.userId,
            processName = "com.example.target",
            session = "session",
            seq = 0L,
            timestamp = now,
            type = MethodHookWireEnvelope.TYPE_HELLO,
        )
        val body = MethodHookWireJson.encodeEnvelope(envelope)
        return MethodHookWireJson.encodePacket(
            MethodHookWirePacket(spec.id, body, MethodHookHmac.sign("token", body))
        )
    }

    private fun event(spec: MethodHookSpec, variables: Map<String, String>, seq: Long = 1L): String {
        val envelope = MethodHookWireEnvelope(
            hookId = spec.id,
            packageName = spec.packageName,
            userId = spec.userId,
            processName = "com.example.target",
            session = "session",
            seq = seq,
            timestamp = now,
            type = MethodHookWireEnvelope.TYPE_EVENT,
            variables = variables,
        )
        val body = MethodHookWireJson.encodeEnvelope(envelope)
        return MethodHookWireJson.encodePacket(
            MethodHookWirePacket(spec.id, body, MethodHookHmac.sign("token", body))
        )
    }

    @Test
    fun allDeclaredExtractionsPlusMetadataAreAccepted() {
        val extractions = (1..36).map {
            MethodExtraction(
                name = "v$it",
                source = MethodExtractionSource.ARGUMENT,
                argIndex = it - 1,
                path = "",
            )
        }
        val spec = specWith(extractions)
        val verifier = verifierFor(spec)
        val caller = MethodHookCaller(10_001, 0, setOf("com.example.target"))
        val guard = MethodHookReplayGuard { now }
        val vars = linkedMapOf<String, String>()
        MethodHookVariables.reserved.forEach { vars[it] = "x" }
        extractions.forEach { vars[it.name] = "1" }

        assertTrue(verifier.verify(caller, hello(spec), guard) is MethodHookVerification.Accepted)
        assertTrue(
            "元数据 5 + 36 个提取项不应被当成「变量过多」",
            verifier.verify(caller, event(spec, vars), guard) is MethodHookVerification.Accepted,
        )
        assertEquals(vars.size, received.size)
    }

    @Test
    fun paddedExtractionNamesMatchTheNormalizedName() {
        val spec = specWith(
            listOf(
                MethodExtraction(
                    name = "  count  ",
                    source = MethodExtractionSource.RESULT,
                    path = "",
                )
            )
        )
        val verifier = verifierFor(spec)
        val caller = MethodHookCaller(10_001, 0, setOf("com.example.target"))
        val guard = MethodHookReplayGuard { now }
        val vars = mapOf(MethodHookVariables.APP to "com.example.target", "count" to "7")

        assertTrue(verifier.verify(caller, hello(spec), guard) is MethodHookVerification.Accepted)
        assertTrue(
            "发送侧已 trim 的变量名必须与声明匹配",
            verifier.verify(caller, event(spec, vars), guard) is MethodHookVerification.Accepted,
        )
        assertEquals("7", received["count"])
    }
}
