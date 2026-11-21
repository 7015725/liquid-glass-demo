package com.flowglass.engine

import com.flowglass.engine.trigger.NetworkType
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerDispatcher
import com.flowglass.engine.trigger.TriggerEnvironment
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerExecutor
import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSkipRecorder
import com.flowglass.engine.trigger.TriggerSource
import com.flowglass.engine.trigger.TriggerTypes
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 触发器求值时的取值表必须包含规则局部/全局变量；事件输出为空字符串时也必须作为“已设置的空值”发布，
 * 否则 set/empty/unset 条件会给出相反结果。
 */
class TriggerVariableScopeTest {
    private class FakeClock : Clock {
        override fun nowEpochMs(): Long = 0
    }

    private class FakeEnvironment(
        private val persisted: Map<String, Map<String, Any?>> = emptyMap(),
    ) : TriggerEnvironment {
        override fun zoneOffsetMinutes(atEpochMs: Long): Int = 0
        override fun batteryPercent(): Int? = null
        override fun isCharging(): Boolean = false
        override fun isScreenOn(): Boolean = true
        override fun isDndOn(): Boolean? = false
        override fun networkType(): NetworkType = NetworkType.NONE
        override fun persistedVariables(ruleId: String): Map<String, Any?> = persisted[ruleId].orEmpty()
    }

    private class RecordingExecutor : TriggerExecutor {
        val variables = mutableListOf<Map<String, Any?>>()
        val readOnly = mutableListOf<Set<String>>()

        override fun isRunning(rule: TriggerRule): Boolean = false

        override fun execute(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
            readOnlyNames: Set<String>,
        ): Boolean {
            this.variables += variables
            readOnly += readOnlyNames
            return true
        }
    }

    private fun rule(
        id: String,
        triggers: List<TriggerComponent>,
        conditions: List<TriggerComponent> = emptyList(),
    ) = TriggerRule(id = id, cmdId = 1, name = id, enabled = true, triggers = triggers, conditions = conditions)

    private fun dispatcher(env: TriggerEnvironment, executor: TriggerExecutor) =
        TriggerDispatcher(FakeClock(), env, executor, TriggerSkipRecorder { _, _, _, _ -> })

    private fun variableCondition(left: String, op: String, right: String = "") = TriggerComponent(
        typeId = "condition.variable",
        name = "变量判断",
        params = buildJsonObject {
            put("left", JsonPrimitive(left))
            put("op", JsonPrimitive(op))
            put("right", JsonPrimitive(right))
        },
    )

    @Test
    fun aTopLevelConditionSeesTheRulesPersistedVariables() {
        val rule = rule(
            "counter",
            triggers = listOf(TriggerComponent(TriggerTypes.SCREEN_ON, "screen_on")),
            conditions = listOf(variableCondition("{count}", "ge", "3")),
        )
        val executor = RecordingExecutor()
        val d = dispatcher(FakeEnvironment(mapOf("counter" to mapOf("count" to 5))), executor)

        val outcome = d.dispatch(
            TriggerEvent(TriggerKinds.SCREEN_ON, TriggerSource.SCREEN, 0),
            listOf(rule),
        )

        assertTrue(outcome.executions.size == 1, "条件应通过，跳过原因=${outcome.skips.map { it.detail }}")
    }

    @Test
    fun anEmptyNotificationTitleIsPublishedAsAnEmptyValue() {
        val rule = rule("notice", triggers = listOf(TriggerComponent(TriggerTypes.NOTIFICATION, "notification")))
        val executor = RecordingExecutor()
        val d = dispatcher(FakeEnvironment(), executor)

        val outcome = d.dispatch(
            TriggerEvent(
                TriggerKinds.NOTIFICATION, TriggerSource.NOTIFICATION, 0,
                mapOf("notification.app" to "com.example", "notification.title" to ""),
            ),
            listOf(rule),
        )

        val variables = outcome.executions.single().variables
        assertTrue(variables.containsKey("通知标题"), "空标题必须发布为已设置的空值，实际键=${variables.keys}")
        assertEquals("", variables["通知标题"])
        assertTrue("通知标题" in outcome.executions.single().readOnlyNames, "空标题输出应只读")
    }
}
