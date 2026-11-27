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
        assertEquals(36, triggers.size)
        assertEquals(9, conditions.size)
        assertEquals(33, actions.size)
        assertTrue(triggers.all { OutputCatalog.allOutputs(it).isNotEmpty() })
        assertTrue(actions.all { OutputCatalog.allOutputs(it).isNotEmpty() })
        assertTrue(conditions.all { OutputCatalog.allOutputs(it).isEmpty() })
        for (meta in triggers + actions) {
            val keys = OutputCatalog.allOutputs(meta).map { it.key }
            assertEquals(keys.size, keys.distinct().size, "输出键重复：${meta.typeId}")
            assertTrue(keys.none { it == "trigger.firedAt" || it == "trigger.reason" || it == "trigger.name" })
            assertTrue(keys.none { it == "action.status" || it == "action.result" || it == "action.name" })
            assertTrue(OutputCatalog.allOutputs(meta).all { VariableName.isValid(it.defaultName) })
            assertTrue(OutputCatalog.defaultBindings(meta).all { VariableName.isValid(it.name) })
        }
    }

    @Test
    fun shellAndHttpDeclareOnlyTheirSpecificOutputs() {
        val registry = BuiltInComponents.registry()
        val shell = registry.find("action.root_shell")!!
        assertEquals(
            listOf("shell.stdout", "shell.stderr", "shell.code"),
            OutputCatalog.allOutputs(shell).map { it.key },
        )
        val http = registry.find("action.http_request")!!
        assertEquals(
            listOf("http.body", "http.status"),
            OutputCatalog.allOutputs(http).map { it.key },
        )
    }

    @Test
    fun componentsWithoutSpecificOutputsGetOneDedicatedPrimary() {
        val registry = BuiltInComponents.registry()
        val toast = registry.find("action.toast")!!
        val bindings = OutputCatalog.bindingsForNew(toast, setOf("上一步结果"))
        assertEquals(1, bindings.size)
        assertEquals("Toast提示结果", bindings.single().name)
        assertEquals("action.toast.primary", bindings.single().key)
        assertTrue(bindings.single().enabled)

        val vibrate = OutputCatalog.bindingsForNew(registry.find("action.vibrate")!!, emptySet())
        assertEquals("振动结果", vibrate.single().name)
        val notification = OutputCatalog.bindingsForNew(registry.find("action.post_notification")!!, emptySet())
        assertEquals("发送通知结果", notification.single().name)
        val launch = OutputCatalog.bindingsForNew(registry.find("action.launch_app")!!, emptySet())
        assertEquals("启动应用结果", launch.single().name)

        val boot = OutputCatalog.bindingsForNew(registry.find("trigger.boot_completed")!!, emptySet())
        assertEquals("开机完成触发时间", boot.single().name)
        val screen = OutputCatalog.bindingsForNew(registry.find("trigger.screen_on")!!, emptySet())
        assertEquals("亮屏触发时间", screen.single().name)
    }

    @Test
    fun specificActionKeepsOnlyItsDedicatedOutputs() {
        val shell = BuiltInComponents.registry().find("action.root_shell")!!
        val bindings = OutputCatalog.bindingsForNew(shell, emptySet())
        assertEquals(3, bindings.size)
        assertTrue(bindings.all { it.key.startsWith("shell.") })
        assertTrue(bindings.all { it.enabled })
        assertTrue(bindings.none { it.key.startsWith("action.") })
    }

    @Test
    fun codeActionsUseActionSpecificDefaultNames() {
        val registry = BuiltInComponents.registry()
        val shell = registry.find("action.root_shell")!!
        assertEquals(
            listOf("Shell标准输出", "Shell标准错误", "Shell退出码"),
            OutputCatalog.bindingsForNew(shell, emptySet()).map { it.name },
        )
        val script = registry.find("action.script")!!
        assertEquals(
            listOf("JS返回值"),
            OutputCatalog.bindingsForNew(script, emptySet()).map { it.name },
        )
        val java = registry.find("action.java_code")!!
        assertEquals(
            listOf("Java返回值"),
            OutputCatalog.bindingsForNew(java, emptySet()).map { it.name },
        )
    }

    @Test
    fun editorRepairsLegacyGenericBindings() {
        val registry = BuiltInComponents.registry()
        val toast = registry.find("action.toast")!!
        val repaired = OutputCatalog.bindingsForEditor(
            listOf(
                OutputBinding("action.status", "动作状态"),
                OutputBinding("action.result", "动作结果"),
                OutputBinding("action.name", "动作名称"),
            ),
            toast,
        )
        assertEquals(1, repaired.size)
        assertEquals("action.toast.primary", repaired.single().key)
        assertEquals("Toast提示结果", repaired.single().name)

        val shell = registry.find("action.root_shell")!!
        val shellRepaired = OutputCatalog.bindingsForEditor(
            listOf(
                OutputBinding("shell.stdout", "标准输出"),
                OutputBinding("action.status", "动作状态"),
            ),
            shell,
        )
        assertEquals(listOf("shell.stdout", "shell.stderr", "shell.code"), shellRepaired.map { it.key })
        assertEquals("标准输出", shellRepaired.first().name)
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
        assertTrue(normalized.actions.all { it.outputs.size == 1 })
        assertTrue(normalized.actions.all { action -> action.outputs.all { it.enabled } })
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
    fun failurePublishesItsDedicatedResult() = runSuspend {
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
            RuleComponent("a.fail", onError = OnError.CONTINUE, outputs = listOf(
                OutputBinding("a.fail.primary", "失败结果"),
            )),
            RuleComponent("a.echo", params = mapOf("text" to JsonPrimitive("{失败结果}"))),
        ))

        val log = executor.run(rule, "测试")

        assertEquals(StepStatus.FAILURE, log.status)
        assertEquals("\"网络错误\"", seen)
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
            RuleComponent("action.toast", params = mapOf("text" to JsonPrimitive("x")), outputs = listOf(
                OutputBinding("action.toast.primary", "Toast提示结果"),
            )),
            RuleComponent("action.set_var", params = mapOf(
                "name" to JsonPrimitive("Toast提示结果"),
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
    fun currentFileWithLegacyGenericBindingsIsRepairedOnDecode() {
        val raw = """{"formatVersion":6,"rules":[{"id":"r1","name":"旧绑定","type":"auto",
            "triggers":[{"type":"trigger.boot_completed","outputs":[
                {"key":"trigger.firedAt","name":"触发时间"},
                {"key":"trigger.reason","name":"触发原因"},
                {"key":"trigger.name","name":"触发器名称"}]}],
            "actions":[{"type":"action.toast","params":{"text":"x"},"outputs":[
                {"key":"action.status","name":"动作状态"},
                {"key":"action.result","name":"动作结果"},
                {"key":"action.name","name":"动作名称"}]}]}]}"""
        val decoded = RuleJson.decode(raw)
        val trigger = decoded.rules.single().triggers.single()
        assertEquals("trigger.boot_completed.primary", trigger.outputs.single().key)
        assertEquals("开机完成触发时间", trigger.outputs.single().name)
        val action = decoded.rules.single().actions.single()
        assertEquals("action.toast.primary", action.outputs.single().key)
        assertEquals("Toast提示结果", action.outputs.single().name)
    }

    @Test
    fun ruleJsonRoundTripsBindingsAndMigratesV5WithDefaults() {
        val registry = BuiltInComponents.registry()
        val rule = RuleFactory.create(RuleType.MANUAL, "序列化").copy(actions = listOf(
            RuleComponent("action.toast", params = mapOf("text" to JsonPrimitive("x")), outputs = listOf(
                OutputBinding("action.toast.primary", "我的结果"),
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
        assertEquals(1, action.outputs.size)
        assertEquals("action.delay.primary", action.outputs.single().key)
        assertTrue(action.outputs.single().enabled)
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
                OutputBinding("action.toast.primary", "同名"),
                OutputBinding("action.toast.primary", "同名"),
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            ParameterCodec.validateDocument(document(listOf(
                OutputBinding("action.toast.primary", "结果", enabled = false),
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
    fun triggerWithoutSpecificOutputPublishesItsPrimaryTime() {
        val executor = RecordingExecutor()
        val dispatcher = TriggerDispatcher(
            Clock { 0L },
            FakeEnvironment(),
            executor,
            TriggerSkipRecorder { _, _, _, _ -> },
        )
        val rule = TriggerRule(
            id = "r2", cmdId = 2, name = "开机", enabled = true,
            triggers = listOf(TriggerComponent("trigger.boot_completed", "开机完成")),
        )

        val result = dispatcher.dispatch(
            TriggerEvent(TriggerKinds.BOOT_COMPLETED, TriggerSource.BOOT, 0, emptyMap()),
            listOf(rule),
        )

        assertEquals(1, result.executions.size)
        assertEquals("00:00", executor.variables["开机完成触发时间"])
        assertTrue(executor.readOnlyNames.contains("开机完成触发时间"))
        assertTrue(executor.readOnlyNames.none { it == "触发时间" || it == "触发原因" || it == "触发器名称" })
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
        assertTrue(executor.readOnlyNames.none { it == "触发时间" || it == "触发原因" || it == "触发器名称" })
    }
}
