package com.flowglass.engine

import com.flowglass.engine.trigger.NetworkType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VariableConditionTest {
    private val env = object : ConditionEnvironment {
        override fun zoneOffsetMinutes(atEpochMs: Long): Int = 480
        override fun batteryPercent(): Int? = 50
        override fun isCharging(): Boolean = false
        override fun isScreenOn(): Boolean = true
        override fun isDndOn(): Boolean? = false
        override fun networkType(): NetworkType = NetworkType.WIFI
        override fun fileExists(path: String, layer: ExecutionLayer?): Boolean? =
            path in setOf("/sdcard/Download", "/data/adb")
    }

    private fun condition(left: String, op: String, right: String = ""): RuleComponent =
        RuleComponent("condition.variable", params = mapOf(
            "left" to JsonPrimitive(left),
            "op" to JsonPrimitive(op),
            "right" to JsonPrimitive(right),
        ))

    @Test
    fun unsetAndEmptyAreDifferent() {
        val empty = mapOf("空" to "")
        assertTrue(Conditions.evaluate(condition("{空}", "set"), empty, env, 0))
        assertTrue(Conditions.evaluate(condition("{空}", "empty"), empty, env, 0))
        assertFalse(Conditions.evaluate(condition("{空}", "unset"), empty, env, 0))
        assertTrue(Conditions.evaluate(condition("{不存在}", "unset"), empty, env, 0))
        assertFalse(Conditions.evaluate(condition("{不存在}", "empty"), empty, env, 0))
    }

    @Test
    fun numericAndTextComparisonsFollowTheirRules() {
        assertTrue(Conditions.evaluate(condition("{数}", "gt", "5"), mapOf("数" to 6L), env, 0))
        assertTrue(Conditions.evaluate(condition("{数}", "eq", "05"), mapOf("数" to 5L), env, 0))
        assertTrue(Conditions.evaluate(condition("{文本}", "eq", "你好"), mapOf("文本" to "你好"), env, 0))
        assertTrue(Conditions.evaluate(condition("{文本}", "contains", "好"), mapOf("文本" to "你好"), env, 0))
        assertTrue(Conditions.evaluate(condition("{文本}", "not_contains", "坏"), mapOf("文本" to "你好"), env, 0))
        assertTrue(Conditions.evaluate(condition("{真}", "eq", "true"), mapOf("真" to true), env, 0))
        assertTrue(Conditions.evaluate(condition("{假}", "ne", "真"), mapOf("假" to false), env, 0))
    }

    @Test
    fun everyComparisonOperatorHasBothOutcomes() {
        val variables = mapOf("数" to 5L, "空" to "", "有" to "内容")
        assertTrue(Conditions.evaluate(condition("{数}", "gt", "4"), variables, env, 0))
        assertFalse(Conditions.evaluate(condition("{数}", "gt", "5"), variables, env, 0))
        assertTrue(Conditions.evaluate(condition("{数}", "ge", "5"), variables, env, 0))
        assertFalse(Conditions.evaluate(condition("{数}", "ge", "6"), variables, env, 0))
        assertTrue(Conditions.evaluate(condition("{数}", "lt", "6"), variables, env, 0))
        assertFalse(Conditions.evaluate(condition("{数}", "lt", "5"), variables, env, 0))
        assertTrue(Conditions.evaluate(condition("{数}", "le", "5"), variables, env, 0))
        assertFalse(Conditions.evaluate(condition("{数}", "le", "4"), variables, env, 0))
        assertTrue(Conditions.evaluate(condition("{数}", "eq", "05"), variables, env, 0))
        assertFalse(Conditions.evaluate(condition("{数}", "ne", "5"), variables, env, 0))
        assertTrue(Conditions.evaluate(condition("{有}", "contains", "内"), variables, env, 0))
        assertFalse(Conditions.evaluate(condition("{有}", "not_contains", "内"), variables, env, 0))
        assertTrue(Conditions.evaluate(condition("{有}", "not_empty"), variables, env, 0))
        assertFalse(Conditions.evaluate(condition("{空}", "not_empty"), variables, env, 0))
        assertTrue(Conditions.evaluate(condition("/not/a/real/path", "file_not_exists"), emptyMap(), env, 0))
    }

    @Test
    fun invalidIntegerComparisonsFailWithAReason() {
        val result = Conditions.evaluateDetailed(
            condition("{数}", "gt", "不是整数"),
            mapOf("数" to 5L),
            env,
            0,
        )
        assertFalse(result.passed)
        assertTrue(result.detail.contains("不是整数"))
    }

    @Test
    fun fileChecksAcceptVariablesAndDirectPaths() {
        assertTrue(Conditions.evaluate(condition("/sdcard/Download", "file_exists"), emptyMap(), env, 0))
        assertFalse(Conditions.evaluate(condition("/sdcard/Download", "file_not_exists"), emptyMap(), env, 0))
        assertTrue(
            Conditions.evaluate(
                condition("{路径}", "file_exists"),
                mapOf("路径" to "/data/adb"),
                env,
                0,
            ),
        )
        assertTrue(Conditions.evaluate(condition("/data/adb", "file_exists"), emptyMap(), env, 0, ExecutionLayer.ROOT))
    }

    @Test
    fun pathsRejectControlCharactersAndQuoteShellWords() {
        assertNull(ConditionPaths.validate("a\nb"))
        assertNull(ConditionPaths.validate("a\u0000b"))
        assertNull(ConditionPaths.validate(""))
        assertEquals("test -e '/sdcard/Download'", ConditionPaths.rootTestCommand("/sdcard/Download"))
        assertEquals(
            "test -e 'a'\\''; touch /tmp/x'",
            ConditionPaths.rootTestCommand("a'; touch /tmp/x"),
        )
        val injected = Conditions.evaluateDetailed(
            condition("a\nb", "file_exists"),
            emptyMap(),
            env,
            0,
        )
        assertFalse(injected.passed)
        assertTrue(injected.detail.contains("换行"))
    }
}
