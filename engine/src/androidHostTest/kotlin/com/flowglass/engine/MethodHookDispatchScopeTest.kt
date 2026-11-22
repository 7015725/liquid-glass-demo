package com.flowglass.engine

import com.flowglass.engine.methodhook.AppMethodCall
import com.flowglass.engine.trigger.NetworkType
import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerDispatcher
import com.flowglass.engine.trigger.TriggerEnvironment
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerExecutor
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSkipRecorder
import com.flowglass.engine.trigger.TriggerSource
import com.flowglass.engine.trigger.TriggerTypes
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 方法钩子事件必须只落到签发它的那个触发器：同一应用里另一个方法的钩子不能把提取数据
 * 喂给这条规则。
 */
class MethodHookDispatchScopeTest {
    private class FakeClock : Clock {
        override fun nowEpochMs(): Long = 0
    }

    private class FakeEnvironment : TriggerEnvironment {
        override fun zoneOffsetMinutes(atEpochMs: Long): Int = 0
        override fun batteryPercent(): Int? = null
        override fun isCharging(): Boolean = false
        override fun isScreenOn(): Boolean = true
        override fun isDndOn(): Boolean? = false
        override fun networkType(): NetworkType = NetworkType.NONE
    }

    private class RecordingExecutor : TriggerExecutor {
        val executed = mutableListOf<String>()
        override fun isRunning(rule: TriggerRule): Boolean = false
        override fun execute(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
            readOnlyNames: Set<String>,
        ): Boolean {
            executed += rule.id
            return true
        }
    }

    private fun hookTrigger(itemId: Long) = TriggerComponent(
        typeId = TriggerTypes.APP_METHOD_CALL,
        name = "应用方法调用",
        itemId = itemId,
        params = buildJsonObject {
            put("package", JsonPrimitive("com.example.target"))
            put("className", JsonPrimitive("com.example.Target"))
            put("methodName", JsonPrimitive("pay"))
            put("paramTypes", JsonPrimitive(""))
            put("extractions", JsonPrimitive(""))
        },
    )

    private fun rule(id: String, cmdId: Long, itemId: Long) = TriggerRule(
        id = id, cmdId = cmdId, name = id, enabled = true, triggers = listOf(hookTrigger(itemId)),
    )

    @Test
    fun onlyTheHookThatSignedThePacketFiresItsRule() {
        val first = rule("first", cmdId = 7, itemId = 11)
        val second = rule("second", cmdId = 7, itemId = 12)
        val executor = RecordingExecutor()
        val dispatcher = TriggerDispatcher(FakeClock(), FakeEnvironment(), executor, TriggerSkipRecorder { _, _, _, _ -> })

        val event = TriggerEvent(
            kind = TriggerKinds.METHOD_CALL,
            source = TriggerSource.METHOD_HOOK,
            atEpochMs = 0,
            data = mapOf(
                "app" to "com.example.target",
                "method.class" to "com.example.Target",
                "method.name" to "pay",
                "method.parameterTypes" to "",
                // The host stamps the verified hook id; only call-7-11 belongs to `first`.
                "hook.id" to AppMethodCall.hookId(7, 11),
            ),
        )

        dispatcher.dispatch(event, listOf(first, second))

        assertEquals(listOf("first"), executor.executed)
    }
}
