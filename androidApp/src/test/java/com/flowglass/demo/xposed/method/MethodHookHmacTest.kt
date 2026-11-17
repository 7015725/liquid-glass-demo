package com.flowglass.demo.xposed.method

import com.flowglass.engine.methodhook.MethodHookSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MethodHookHmacTest {
    private val token = "test-token-32-bytes"
    private val body = """{"hookId":"h1","seq":1}"""

    @Test
    fun signatureIsDeterministicAndTamperEvident() {
        val mac = MethodHookHmac.sign(token, body)
        assertTrue(mac.isNotBlank())
        assertTrue(MethodHookHmac.verify(token, body, mac))
        assertFalse(MethodHookHmac.verify(token, "$body ", mac))
        assertFalse(MethodHookHmac.verify("$token-x", body, mac))
        assertFalse(MethodHookHmac.verify(token, body, mac.dropLast(1) + "A"))
    }

    @Test
    fun emptyInputsAreNeverAccepted() {
        assertFalse(MethodHookHmac.verify("", body, MethodHookHmac.sign(token, body)))
        assertFalse(MethodHookHmac.verify(token, body, ""))
    }

    @Test
    fun callerMustMatchUserAndPackage() {
        val spec = MethodHookSpec(
            id = "h1",
            packageName = "com.example.target",
            userId = 0,
            className = "com.example.Target",
            methodName = "run",
        )
        assertTrue(MethodHookCallerValidator.matches(MethodHookCaller(10001, 0, setOf("com.example.target")), spec))
        assertFalse(MethodHookCallerValidator.matches(MethodHookCaller(10001, 10, setOf("com.example.target")), spec))
        assertFalse(MethodHookCallerValidator.matches(MethodHookCaller(10001, 0, setOf("com.example.other")), spec))
        assertFalse(MethodHookCallerValidator.matches(MethodHookCaller(-1, 0, emptySet()), spec))
    }
}
