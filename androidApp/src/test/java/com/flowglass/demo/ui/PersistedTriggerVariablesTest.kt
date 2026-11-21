package com.flowglass.demo.ui

import com.flowglass.engine.GlobalVariableMeta
import com.flowglass.engine.LocalVariable
import com.flowglass.engine.Rule
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 触发器条件拿到的持久变量：同名时局部遮蔽全局（与运行体一致），
 * 单个全局变量值/类型不一致时跳过该键，而不是让整条触发器采集协程崩掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistedTriggerVariablesTest {
    @Test
    fun localShadowsGlobalAndBadValuesAreSkipped() {
        val state = AppState()
        state.engineGlobals = mapOf(
            "dup" to JsonPrimitive("global-value"),
            "g1" to JsonPrimitive("seven"),
            "bad" to JsonPrimitive("not-a-number"),
        )
        state.engineGlobalMeta = mapOf(
            "dup" to GlobalVariableMeta(type = "text"),
            "g1" to GlobalVariableMeta(type = "text"),
            "bad" to GlobalVariableMeta(type = "int"),
        )
        state.engineRules = listOf(
            Rule(
                id = "r1",
                name = "r1",
                locals = listOf(LocalVariable("dup", "text", JsonPrimitive("local-value"))),
            ),
        )

        val values = state.persistedVariablesFor("r1")

        assertEquals("local-value", values["dup"])
        assertEquals("seven", values["g1"])
        assertFalse("类型不符的全局变量必须被跳过而不是抛异常", values.containsKey("bad"))
    }
}
