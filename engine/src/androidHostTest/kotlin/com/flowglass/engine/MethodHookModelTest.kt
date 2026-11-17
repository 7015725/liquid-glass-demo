package com.flowglass.engine

import com.flowglass.engine.methodhook.MethodExtraction
import com.flowglass.engine.methodhook.MethodExtractionSource
import com.flowglass.engine.methodhook.MethodHookFile
import com.flowglass.engine.methodhook.MethodHookJson
import com.flowglass.engine.methodhook.MethodHookResolver
import com.flowglass.engine.methodhook.MethodHookSpec
import com.flowglass.engine.methodhook.MethodHookValidation
import com.flowglass.engine.methodhook.MethodHookVariables
import com.flowglass.engine.methodhook.MethodResolution
import com.flowglass.engine.methodhook.MethodSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodHookModelTest {
    private fun spec(
        paramCount: Int? = null,
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
        paramCount = paramCount,
        paramTypes = paramTypes,
        extractions = extractions,
        maxCallsPerMinute = maxCalls,
        burst = burst,
    )

    @Test
    fun newHooksAreDisabledByDefault() {
        assertFalse(MethodHookSpec(
            id = "h1",
            packageName = "com.example.target",
            className = "com.example.Target",
            methodName = "submit",
        ).enabled)
    }

    @Test
    fun resolverRequiresAUniqueOverload() {
        val methods = listOf(
            MethodSignature(listOf("java.lang.String")),
            MethodSignature(listOf("java.lang.String", "int")),
        )
        assertEquals(
            MethodResolution.Ambiguous(methods),
            MethodHookResolver.resolve(methods),
        )
        assertEquals(
            MethodResolution.Unique(MethodSignature(listOf("java.lang.String", "int"))),
            MethodHookResolver.resolve(methods, paramCount = 2),
        )
        assertEquals(
            MethodResolution.Unique(MethodSignature(listOf("java.lang.String"))),
            MethodHookResolver.resolve(methods, paramTypes = listOf("java.lang.String")),
        )
        assertEquals(MethodResolution.NotFound, MethodHookResolver.resolve(methods, paramCount = 3))
        assertEquals(
            MethodResolution.NotFound,
            MethodHookResolver.resolve(methods, paramTypes = listOf("int")),
        )
    }

    @Test
    fun validationAllowsChineseExtractionNamesAndRejectsDuplicatesAndReservedNames() {
        val valid = spec(
            paramCount = 2,
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
    fun validationRejectsBadPathsCountsAndRateLimits() {
        val tooDeep = spec(
            extractions = listOf(MethodExtraction("值", MethodExtractionSource.RESULT, path = "a.b.c.d.e")),
        )
        assertTrue(MethodHookValidation.errors(tooDeep).any { it.contains("字段路径") })

        val mismatch = spec(paramCount = 2, paramTypes = listOf("java.lang.String"))
        assertTrue(MethodHookValidation.errors(mismatch).any { it.contains("数量不一致") })

        val badRate = spec(maxCalls = 0, burst = 10)
        assertTrue(MethodHookValidation.errors(badRate).any { it.contains("每分钟") })

        val badBurst = spec(maxCalls = 10, burst = 11)
        assertTrue(MethodHookValidation.errors(badBurst).any { it.contains("突发") })
    }

    @Test
    fun codecRoundTripsAndRejectsAnotherFormatVersion() {
        val file = MethodHookFile(hooks = listOf(spec()))
        val decoded = MethodHookJson.decode(MethodHookJson.encode(file))
        assertEquals(file, decoded)

        val wrongVersion = MethodHookJson.encode(file).replace("\"formatVersion\": 1", "\"formatVersion\": 2")
        assertTrue(runCatching { MethodHookJson.decode(wrongVersion) }.isFailure)
    }
}
