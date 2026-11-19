package com.flowglass.engine

import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerDispatcher
import com.flowglass.engine.trigger.TriggerEnvironment
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerExecutor
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSource
import com.flowglass.engine.trigger.TriggerSkipRecorder
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutputVariablesTest {
    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return checkNotNull(result) { "测试协程发生了非同步挂起" }.getOrThrow()
    }

    private fun actionMeta(
        type: String,
        outputs: List<OutputSpec> = emptyList(),
        params: List<ParamSpec> = listOf(ParamSpec("text", "文本")),
    ) = ComponentMeta(
        typeId = type,
        name = type,
        category = "测试",
        kind = ComponentKind.ACTION,
        params = params,
        outputs = outputs,
        supports = listOf(LayerSupport(ExecutionLayer.NORMAL)),
    )

    private fun executor(
        registry: ComponentRegistry,
        handlers: Map<String, Action>,
    ) = RuleExecutor(
        registry,
        handlers,
        CancellationCheckpoint { },
    )

    @Test
    fun everyTriggerAndActionHasOutputsAndConditionsHaveNone() {
        val registry = BuiltInComponents.registry()
        val triggers = registry.byKind(ComponentKind.TRIGGER)
        val conditions = registry.byKind(ComponentKind.CONDITION)
        val actions = registry.byKind(ComponentKind.ACTION)
        assertEquals(23, triggers.size)
        assertEquals(9, conditions.size)
        assertEquals(32, actions.size)
        assertTrue(triggers.all { OutputCatalog.allOutputs(it).isNotEmpty() })
        assertTrue(actions.all { OutputCatalog.allOutputs(it).isNotEmpty() })
        assertTrue(conditions.all { OutputCatalog.allOutputs(it).isEmpty() })
        for (meta in triggers + actions) {
            val keys = OutputCatalog.allOutputs(meta).map { it.key }
            assertEquals(keys.size, keys.distinct().size, "输出键重复：${meta.typeId}")
            assertTrue(OutputCatalog.allOutputs(meta).all { VariableName.isValid(it.defaultName) })
        }
    }

    @Test
    fun shellAndHttpDeclareTheirSpecificOutputs() {
        val registry = BuiltInComponents.registry()
        val shell = registry.find("action.root_shell")!!
        assertEquals(
            listOf("shell.stdout", "shell.stderr", "shell.code", "action.status", "action.result", "action.name"),
            OutputCatalog.allOutputs(shell).map { it.key },
        )
        val http = registry.find("action.http_request")!!
        assertTrue(OutputCatalog.allOutputs(http).map { it.key }.containsAll(listOf("http.body", "http.status")))
    }

    @Test
    fun twoInstancesGetIndependentNamesAndConditionsAreCleared() {
        val registry = BuiltInComponents.registry()
        val toast = registry.find("action.toast")!!
        val rule = RuleFactory.create(RuleType.MANUAL, "规则").copy(
            triggers = listOf(RuleComponent("trigger.screen_on")),
            conditions = listOf(RuleComponent("condition.variable", outputs = OutputCatalog.defaultBindings(toast))),
            actions = listOf(
                RuleComponent("action.toast", params = mapOf("text" to JsonPrimitive("a"))),
                RuleComponent("action.toast", params = mapOf("text" to JsonPrimitive("b"))),
            ),
        )

        val normalized = OutputCatalog.normalizeRule(rule, registry)

        assertTrue(normalized.conditions.single().outputs.isEmpty())
        val names = normalized.actions.flatMap { action -> action.outputs.map { it.name } }
        assertEquals(names.size, names.distinct().size)
        assertTrue(normalized.actions.all { it.outputs.size == 3 })
        assertTrue(normalized.actions.all { action -> action.outputs.none { it.key.startsWith("trigger.") } })
    }

    @Test
    fun anOutputIsVisibleOnlyAfterItsActionAndKeepsItsType() = runSuspend {
        val registry = ComponentRegistry().apply {
            register(actionMeta("a.produce", listOf(OutputSpec("payload", "负载", VariableType.INT, "负载"))))
            register(actionMeta("a.echo"))
        }
        val seen = mutableListOf<String?>()
        val executor = executor(registry, mapOf(
            "a.produce" to Action { _, _ ->
                ActionOutcome(StepStatus.SUCCESS, "完成", mapOf("payload" to 7))
            },
            "a.echo" to Action { _, component ->
                seen += component.params["text"]?.toString()
                ActionOutcome(StepStatus.SUCCESS, "好")
            },
        ))
        val rule = RuleFactory.create(RuleType.MANUAL, "输出").copy(actions = listOf(
            RuleComponent("a.echo", params = mapOf("text" to JsonPrimitive("{负载}"))),
            RuleComponent("a.produce"),
            RuleComponent("a.echo", params = mapOf("text" to JsonPrimitive("{负载}"))),
        ))

        val log = executor.run(rule, "测试")

        assertEquals(StepStatus.SUCCESS, log.status)
        assertEquals("\"{负载}\"", seen[0])
        assertEquals("\"7\"", seen[1])
    }

    @Test
    fun failurePublishesGenericStatusAndResult() = runSuspend {
        val registry = ComponentRegistry().apply {
            register(actionMeta("a.fail"))
            register(actionMeta("a.echo"))
        }
        var seen: String? = null
        val executor = executor(registry, mapOf(
            "a.fail" to Action { _, _ -> ActionOutcome(StepStatus.FAILURE, "网络错误") },
            "a.echo" to Action { _, component ->
                seen = component.params["text"]?.toString()
                ActionOutcome(StepStatus.SUCCESS, "好")
            },
        ))
        val rule = RuleFactory.create(RuleType.MANUAL, "失败").copy(actions = listOf(
            RuleComponent("a.fail", onError = OnError.CONTINUE),
            RuleComponent("a.echo", params = mapOf("text" to JsonPrimitive("{动作状态}|{动作结果}"))),
        ))

        val log = executor.run(rule, "测试")

        assertEquals(StepStatus.FAILURE, log.status)
        assertEquals("\"失败|网络错误\"", seen)
    }

    @Test
    fun disabledBindingIsNotPublished() = runSuspend {
        val registry = ComponentRegistry().apply {
            register(actionMeta("a.produce", listOf(
                OutputSpec("payload", "负载", VariableType.TEXT, "负载"),
                OutputSpec("extra", "附加", VariableType.TEXT, "附加"),
            )))
            register(actionMeta("a.echo"))
        }
        var seen: String? = null
        val executor = executor(registry, mapOf(
            "a.produce" to Action { _, _ ->
                ActionOutcome(StepStatus.SUCCESS, "完成", mapOf("payload" to "A", "extra" to "B"))
            },
            "a.echo" to Action { _, component ->
                seen = component.params["text"]?.toString()
                ActionOutcome(StepStatus.SUCCESS, "好")
            },
        ))
        val rule = RuleFactory.create(RuleType.MANUAL, "禁用").copy(actions = listOf(
            RuleComponent("a.produce", outputs = listOf(
                OutputBinding("payload", "负载"),
                OutputBinding("extra", "附加", enabled = false),
            )),
            RuleComponent("a.echo", params = mapOf("text" to JsonPrimitive("{附加}"))),
        ))

        executor.run(rule, "测试")

        assertEquals("\"{附加}\"", seen)
    }

    @Test
    fun outputNamesAreReadOnlyForTheSetVariableAction() = runSuspend {
        val registry = BuiltInComponents.registry()
        assertTrue(registry.find("action.toast") != null)
        val rule = RuleFactory.create(RuleType.MANUAL, "只读").copy(actions = listOf(
            RuleComponent("action.toast", params = mapOf("text" to JsonPrimitive("x"))),
            RuleComponent("action.set_var", params = mapOf(
                "name" to JsonPrimitive("动作状态"),
                "type" to JsonPrimitive("text"),
                "value" to JsonPrimitive("伪造"),
                "scope" to JsonPrimitive("local"),
            )),
        ))

        val log = executor(registry, mapOf(
            "action.toast" to Action { _, _ -> ActionOutcome(StepStatus.SUCCESS, "已提示") },
        )).run(rule, "测试")

        assertEquals(StepStatus.FAILURE, log.status)
        assertTrue(log.steps.last().detail.contains("只读"), log.steps.joinToString(" | ") { it.detail })
    }

    @Test
    fun ruleJsonRoundTripsBindingsAndMigratesV5WithDefaults() {
        val registry = BuiltInComponents.registry()
        val rule = RuleFactory.create(RuleType.MANUAL, "序列化").copy(actions = listOf(
            RuleComponent("action.toast", params = mapOf("text" to JsonPrimitive("x")), outputs = listOf(
                OutputBinding("action.status", "我的状态"),
                OutputBinding("action.result", "我的结果"),
                OutputBinding("action.name", "我的名称", enabled = false),
            )),
        ))
        val document = RuleDocument(rules = listOf(rule))

        val decoded = RuleJson.decode(RuleJson.encode(document))
        assertEquals(CURRENT_FORMAT_VERSION, decoded.formatVersion)
        assertEquals(rule.actions.single().outputs, decoded.rules.single().actions.single().outputs)

        val legacy = RuleJson.decode(
            """{"formatVersion":5,"rules":[{"id":"r1","name":"旧规则","type":"manual",
               "actions":[{"type":"action.delay","params":{"duration":1000}}]}]}""",
        )
        val action = legacy.rules.single().actions.single()
        assertEquals(3, action.outputs.size)
        assertTrue(action.outputs.any { it.key == "action.status" && it.enabled })
        assertTrue(action.outputs.none { it.key.startsWith("trigger.") })
        assertEquals(6, legacy.formatVersion)
    }

    @Test
    fun validationRejectsUnknownDuplicateAndDisabledBindings() {
        val registry = BuiltInComponents.registry()
        fun document(outputs: List<OutputBinding>) = RuleDocument(rules = listOf(
            RuleFactory.create(RuleType.MANUAL, "校验").copy(actions = listOf(
                RuleComponent("action.toast", params = mapOf("text" to JsonPrimitive("x")), outputs = outputs),
            )),
        ))

        assertFailsWith<IllegalArgumentException> {
            ParameterCodec.validateDocument(document(listOf(OutputBinding("nope", "未知"))))
        }
        assertFailsWith<IllegalArgumentException> {
            ParameterCodec.validateDocument(document(listOf(
                OutputBinding("action.status", "同名"),
                OutputBinding("action.result", "同名"),
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            ParameterCodec.validateDocument(document(listOf(
                OutputBinding("action.status", "状态", enabled = false),
                OutputBinding("action.result", "结果", enabled = false),
                OutputBinding("action.name", "名称", enabled = false),
            )))
        }
    }

    private class FakeEnvironment : TriggerEnvironment {
        override fun zoneOffsetMinutes(atEpochMs: Long): Int = 0
        override fun batteryPercent(): Int? = null
        override fun isCharging(): Boolean = false
        override fun isScreenOn(): Boolean = true
        override fun isDndOn(): Boolean? = false
        override fun networkType(): com.flowglass.engine.trigger.NetworkType =
            com.flowglass.engine.trigger.NetworkType.NONE
        override fun appProcessRunning(packageName: String, userId: Int): Boolean? = null
    }

    private class RecordingExecutor : TriggerExecutor {
        var variables: Map<String, Any?> = emptyMap()
        var readOnlyNames: Set<String> = emptySet()
        override fun isRunning(rule: TriggerRule): Boolean = false
        override fun execute(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
            readOnlyNames: Set<String>,
        ): Boolean {
            this.variables = variables
            this.readOnlyNames = readOnlyNames
            return true
        }
    }

    @Test
    fun triggerOutputsExistBeforeConditionsAndExecution() {
        val executor = RecordingExecutor()
        val dispatcher = TriggerDispatcher(
            Clock { 0L },
            FakeEnvironment(),
            executor,
            TriggerSkipRecorder { _, _, _, _ -> },
        )
        val rule = TriggerRule(
            id = "r1", cmdId = 1, name = "通知", enabled = true,
            triggers = listOf(TriggerComponent("trigger.notification", "通知")),
            conditions = listOf(TriggerComponent(
                "condition.variable",
                "变量判断",
                params = mapOf(
                    "left" to JsonPrimitive("{通知标题}"),
                    "op" to JsonPrimitive("eq"),
                    "right" to JsonPrimitive("标题"),
                ),
            )),
        )

        val result = dispatcher.dispatch(
            TriggerEvent(TriggerKinds.NOTIFICATION, TriggerSource.NOTIFICATION, 0, mapOf(
                "notification.app" to "com.example",
                "notification.title" to "标题",
                "notification.text" to "正文",
            )),
            listOf(rule),
        )

        assertEquals(1, result.executions.size)
        assertEquals("标题", executor.variables["通知标题"])
        assertTrue(executor.readOnlyNames.contains("通知标题"))
        assertTrue(executor.readOnlyNames.contains("触发时间"))
    }
}
