package com.flowglass.engine

import com.flowglass.engine.methodhook.AppMethodCall
import com.flowglass.engine.methodhook.MethodExtraction
import com.flowglass.engine.methodhook.MethodExtractionSource
import com.flowglass.engine.methodhook.MethodHookRuntimeConfig
import com.flowglass.engine.methodhook.MethodHookRuntimeHook
import com.flowglass.engine.methodhook.MethodHookRuntimeJson
import com.flowglass.engine.methodhook.MethodHookResolver
import com.flowglass.engine.methodhook.MethodHookSpec
import com.flowglass.engine.methodhook.MethodHookValidation
import com.flowglass.engine.methodhook.MethodHookVariables
import com.flowglass.engine.methodhook.MethodResolution
import com.flowglass.engine.methodhook.MethodSignature
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodHookModelTest {
    private fun spec(
        paramTypes: List<String> = emptyList(),
        extractions: List<MethodExtraction> = emptyList(),
        maxCalls: Int = 60,
        burst: Int = 10,
    ) = MethodHookSpec(
        id = "h1",
        packageName = "com.example.target",
        userId = 0,
        className = "com.example.target.OrderApi",
        methodName = "submit",
        paramTypes = paramTypes,
        extractions = extractions,
        maxCallsPerMinute = maxCalls,
        burst = burst,
    )

    @Test
    fun emptyParamTypesObserveEveryOverload() {
        val methods = listOf(
            MethodSignature(listOf("java.lang.String")),
            MethodSignature(listOf("java.lang.String", "int")),
        )
        assertEquals(MethodResolution.All(methods), MethodHookResolver.resolve(methods))
        assertEquals(
            MethodResolution.Unique(MethodSignature(listOf("java.lang.String", "int"))),
            MethodHookResolver.resolve(methods, paramTypes = listOf("java.lang.String", "int")),
        )
        assertEquals(MethodResolution.NotFound, MethodHookResolver.resolve(methods, paramTypes = listOf("int")))
    }

    @Test
    fun validationAllowsChineseExtractionNamesAndRejectsDuplicatesAndReservedNames() {
        val valid = spec(
            extractions = listOf(
                MethodExtraction("订单号", MethodExtractionSource.ARGUMENT, 0),
                MethodExtraction("金额", MethodExtractionSource.RESULT, path = "amount.text"),
            ),
        )
        assertTrue(MethodHookValidation.errors(valid).isEmpty())

        val duplicate = valid.copy(
            extractions = valid.extractions + MethodExtraction("订单号", MethodExtractionSource.ARGUMENT, 1),
        )
        assertTrue(MethodHookValidation.errors(duplicate).any { it.contains("重名") })

        val reserved = valid.copy(
            extractions = valid.extractions + MethodExtraction(MethodHookVariables.APP, MethodExtractionSource.RESULT),
        )
        assertTrue(MethodHookValidation.errors(reserved).any { it.contains("内置变量") })

        val sharedName = MethodHookValidation.errors(valid, existingNames = setOf("订单号"))
        assertTrue(sharedName.any { it.contains("全局唯一") })
    }

    @Test
    fun validationRejectsBadPathsAndRateLimits() {
        val tooDeep = spec(
            extractions = listOf(MethodExtraction("值", MethodExtractionSource.RESULT, path = "a.b.c.d.e")),
        )
        assertTrue(MethodHookValidation.errors(tooDeep).any { it.contains("字段路径") })

        val badRate = spec(maxCalls = 0, burst = 10)
        assertTrue(MethodHookValidation.errors(badRate).any { it.contains("每分钟") })

        val badBurst = spec(maxCalls = 10, burst = 11)
        assertTrue(MethodHookValidation.errors(badBurst).any { it.contains("突发") })
    }

    @Test
    fun triggerParametersValidateClassMethodAndTypes() {
        fun params(
            packageName: String = "com.example.target",
            className: String = "com.example.app.OrderManager",
            methodName: String = "onPaySuccess",
            paramTypes: String = "java.lang.String,int",
        ) = mapOf(
            AppMethodCall.KEY_PACKAGE to JsonPrimitive(packageName),
            AppMethodCall.KEY_CLASS to JsonPrimitive(className),
            AppMethodCall.KEY_METHOD to JsonPrimitive(methodName),
            AppMethodCall.KEY_PARAM_TYPES to JsonPrimitive(paramTypes),
            AppMethodCall.KEY_EXTRACTIONS to JsonPrimitive("[]"),
        )

        assertTrue(AppMethodCall.validateParams(params()).isEmpty())
        assertTrue(AppMethodCall.validateParams(params(className = "com..Bad"))
            .any { it.contains("类名") })
        assertTrue(AppMethodCall.validateParams(params(methodName = "on pay"))
            .any { it.contains("方法名") })
        assertTrue(AppMethodCall.validateParams(params(paramTypes = "java..lang.String,int"))
            .any { it.contains("参数类型") })
        assertTrue(AppMethodCall.validateParams(params(packageName = "com.example.target@bad"))
            .any { it.contains("目标应用") })
    }

    @Test
    fun extractionsRoundTripThroughTriggerParameters() {
        val extraction = MethodExtraction("订单号", MethodExtractionSource.ARGUMENT, 0)
        val encoded = AppMethodCall.encodeExtractions(listOf(extraction))
        val params = mapOf(
            AppMethodCall.KEY_PACKAGE to JsonPrimitive("com.example.target@10"),
            AppMethodCall.KEY_CLASS to JsonPrimitive("com.example.Target"),
            AppMethodCall.KEY_METHOD to JsonPrimitive("submit"),
            AppMethodCall.KEY_PARAM_TYPES to JsonPrimitive("java.lang.String"),
            AppMethodCall.KEY_EXTRACTIONS to JsonPrimitive(encoded),
        )
        val spec = requireNotNull(AppMethodCall.spec("call-1-2", params))
        assertEquals("com.example.target", spec.packageName)
        assertEquals(10, spec.userId)
        assertEquals(listOf(extraction), spec.extractions)
        assertEquals(setOf("订单号"), AppMethodCall.extractionNames(params))
    }

    @Test
    fun runtimeConfigRoundTripsWithoutLosingTheToken() {
        val config = MethodHookRuntimeConfig(
            hooks = listOf(MethodHookRuntimeHook(spec(), "secret-token")),
        )
        val decoded = MethodHookRuntimeJson.decode(MethodHookRuntimeJson.encode(config))
        assertEquals("secret-token", decoded.hooks.single().token)
        assertEquals(spec(), decoded.hooks.single().spec)
    }
}
