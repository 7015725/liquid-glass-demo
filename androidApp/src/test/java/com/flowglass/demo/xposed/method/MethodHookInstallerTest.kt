package com.flowglass.demo.xposed.method

import com.flowglass.demo.xposed.HookBridge
import com.flowglass.demo.xposed.HookCallback
import com.flowglass.engine.methodhook.MethodExtraction
import com.flowglass.engine.methodhook.MethodExtractionSource
import com.flowglass.engine.methodhook.MethodHookRuntimeConfig
import com.flowglass.engine.methodhook.MethodHookRuntimeHook
import com.flowglass.engine.methodhook.MethodHookSpec
import com.flowglass.engine.methodhook.MethodHookStatus
import com.flowglass.engine.methodhook.MethodHookVariables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MethodHookInstallerTest {
    private class Target {
        fun submit(orderId: String, amount: Int): String = "ok:$orderId:$amount"
        fun overloaded(value: String): String = value
        fun overloaded(value: String, count: Int): String = value.repeat(count)
    }

    private class FakeBridge : HookBridge {
        var hooked: Method? = null
        var callback: HookCallback? = null

        override val apiLevel: Int = 101
        override fun log(message: String) = Unit
        override fun findClass(name: String, loader: ClassLoader): Class<*> = Target::class.java
        override fun hookAllMethods(clazz: Class<*>, methodName: String, callback: HookCallback): Int = 0
        override fun hookMethod(method: Method, callback: HookCallback): Boolean {
            hooked = method
            this.callback = callback
            return true
        }

        override fun getObjectField(instance: Any?, name: String): Any? = null
        override fun callMethod(instance: Any?, name: String, vararg args: Any?): Any? = null
        override fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any? = null
        override fun readPreferences(name: String): Map<String, String> = emptyMap()
    }

    private class Recorder : MethodHookEmitter {
        val events = mutableListOf<Triple<String, Map<String, String>, String?>>()
        override fun emit(
            spec: MethodHookSpec,
            type: String,
            variables: Map<String, String>,
            status: String?,
            detail: String?,
        ) {
            events += Triple(type, variables, status)
        }
    }

    private fun spec(
        methodName: String = "submit",
        paramCount: Int? = 2,
        paramTypes: List<String> = emptyList(),
        extractions: List<MethodExtraction> = listOf(
            MethodExtraction("订单号", MethodExtractionSource.ARGUMENT, 0),
            MethodExtraction("结果", MethodExtractionSource.RESULT),
        ),
        burst: Int = 1,
    ) = MethodHookSpec(
        id = "h1",
        packageName = "com.example.target",
        className = Target::class.java.name,
        methodName = methodName,
        paramCount = paramCount,
        paramTypes = paramTypes,
        extractions = extractions,
        maxCallsPerMinute = 60,
        burst = burst,
    )

    private fun config(spec: MethodHookSpec) = MethodHookRuntimeConfig(
        hooks = listOf(MethodHookRuntimeHook(spec, "token")),
    )

    @Test
    fun installsUniqueOverloadAndExtractsOnlyDeclaredValues() {
        val bridge = FakeBridge()
        val recorder = Recorder()
        val result = MethodHookInstaller.install(
            bridge, Target::class.java.classLoader, "com.example.target", 0, "com.example.target",
            config(spec()), recorder,
        )
        assertEquals(listOf("installed"), result)
        assertTrue(recorder.events.any { it.first == "install" && it.third == MethodHookStatus.INSTALLED })

        bridge.callback!!.afterCall(null, arrayOf("A-9", 42), "done")
        val event = recorder.events.last { it.first == "event" }.second
        assertEquals("A-9", event["订单号"])
        assertEquals("done", event["结果"])
        assertEquals("com.example.target", event[MethodHookVariables.APP])
        assertEquals("h1", event[MethodHookVariables.ID])
        assertEquals("submit(java.lang.String,int)", event[MethodHookVariables.SIGNATURE])
    }

    @Test
    fun refusesAmbiguousAndMissingMethodsWithoutHooking() {
        val bridge = FakeBridge()
        val recorder = Recorder()
        val ambiguous = MethodHookInstaller.install(
            bridge, Target::class.java.classLoader, "com.example.target", 0, "com.example.target",
            config(spec(methodName = "overloaded", paramCount = null)), recorder,
        )
        assertEquals(listOf("ambiguous"), ambiguous)
        assertEquals(null, bridge.hooked)

        val missing = MethodHookInstaller.install(
            bridge, Target::class.java.classLoader, "com.example.target", 0, "com.example.target",
            config(spec(methodName = "nope")), recorder,
        )
        assertEquals(listOf("not_found"), missing)
    }

    @Test
    fun rateLimitSuppressesTheEventAfterTheBurst() {
        val bridge = FakeBridge()
        val recorder = Recorder()
        MethodHookInstaller.install(
            bridge, Target::class.java.classLoader, "com.example.target", 0, "com.example.target",
            config(spec(burst = 1)), recorder,
        )
        bridge.callback!!.afterCall(null, arrayOf("A-1", 1), "one")
        bridge.callback!!.afterCall(null, arrayOf("A-2", 2), "two")
        assertEquals(1, recorder.events.count { it.first == "event" })
        assertTrue(recorder.events.any { it.first == "rate" && it.third == MethodHookStatus.RATE_LIMITED })
    }
}
