package com.flowglass.engine

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class EngineCoreTest {
    @Test
    fun registryRejectsDuplicateIdsAndFiltersByTier() {
        val registry = ComponentRegistry()
        val normal = ComponentMeta("action.normal", "普通", "设备", ComponentKind.ACTION)
        val root = normal.copy(typeId = "action.root", name = "Root", minTier = Tier.ROOT)
        registry.register(normal)
        registry.register(root)

        assertEquals(listOf(normal, root), registry.byCategory("设备"))
        assertEquals(listOf(normal), registry.availableFor(Tier.NORMAL))
        assertEquals(listOf(normal, root), registry.availableFor(Tier.ROOT))
        assertFailsWith<IllegalArgumentException> { registry.register(normal) }
    }

    @Test
    fun ruleJsonRoundTripsVersionTwoAndRejectsUnsupportedVersions() {
        val rule = RuleFactory.create(RuleType.MANUAL, "测试规则").copy(
            enabled = true,
            runMode = RunMode.QUEUE,
            triggerMatch = MatchSpec(mode = MatchMode.CUSTOM, atLeast = 1, windowSeconds = 42),
            locals = listOf(LocalVariable("counter", "int", JsonPrimitive(3), resetEachRun = false)),
            actions = listOf(RuleComponent("action.delay", mapOf("duration" to JsonPrimitive(2000)), OnError.CONTINUE)),
        )
        val document = RuleDocument(
            rules = listOf(rule),
            ui = RuleUiMetadata(presentation = mapOf(rule.id to RulePresentation(legacyId = 101L))),
        )

        val encoded = RuleJson.encode(document)
        val decoded = RuleJson.decode(encoded)

        assertEquals(CURRENT_FORMAT_VERSION, decoded.formatVersion)
        assertEquals(document, decoded)
        assertTrue(encoded.contains("\"formatVersion\":2"))
        assertFailsWith<IllegalArgumentException> { RuleJson.decode("{\"formatVersion\":9,\"rules\":[]}") }
    }

    @Test
    fun ruleFactoryCreatesUuidIdsAndManualRulesHaveNoTriggersOrConditions() {
        val first = RuleFactory.create(RuleType.MANUAL)
        val second = RuleFactory.create(RuleType.MANUAL)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(first.id))
        assertNotEquals(first.id, second.id)
        assertTrue(first.triggers.isEmpty())
        assertTrue(first.conditions.isEmpty())
        assertFailsWith<IllegalArgumentException> { RuleJson.decode("""{"formatVersion":2,"rules":[{"id":"x","type":"other"}]}""") }
    }

    @Test
    fun builtInActionsExposeAllElevenStableTypeIdsAndPermissionTiers() {
        val registry = ComponentRegistry()
        BuiltInActions.registerInto(registry)

        assertEquals(11, registry.byKind(ComponentKind.ACTION).size)
        assertEquals(11, registry.all().map { it.typeId }.distinct().size)
        assertEquals(Tier.ROOT, registry.find("action.root_shell")?.minTier)
        assertEquals(Tier.NORMAL, registry.find("action.post_notification")?.minTier)
        assertTrue(registry.find("action.set_brightness")!!.permissions.contains("write_settings"))
        assertEquals(ParamType.TRI_STATE, registry.find("action.wifi_switch")!!.params.single().type)
        assertEquals(ParamType.APPLICATION, registry.find("action.launch_app")!!.params.single().type)
        assertEquals(ParamExpansion.SHELL_QUOTED, registry.find("action.root_shell")!!.params.single().expansion)
        assertEquals(ParamExpansion.HTTP_REQUEST, registry.find("action.http_request")!!.params.single().expansion)
        assertEquals(ParamExpansion.PLAIN, registry.find("action.post_notification")!!.params.first().expansion)
    }

    @Test
    fun builtInComponentsRegisterTriggersConditionsAndActionsTogether() {
        val registry = BuiltInComponents.registry()
        assertEquals(BuiltInComponents.all.size, registry.all().size)
        assertTrue(registry.byKind(ComponentKind.TRIGGER).any { it.outputs.isNotEmpty() })
        assertTrue(registry.byKind(ComponentKind.CONDITION).isNotEmpty())
        assertEquals(11, registry.byKind(ComponentKind.ACTION).size)
    }

    @Test
    fun validatorChecksManualShapeAndRequiredParameters() {
        val registry = ComponentRegistry()
        BuiltInActions.registerInto(registry)
        val rule = RuleFactory.create(RuleType.MANUAL, "通知").copy(
            actions = listOf(RuleComponent("action.post_notification", emptyMap())),
            triggers = listOf(RuleComponent("trigger.any")),
        )

        val issues = RuleValidator.validate(rule, registry)

        assertTrue(issues.any { it.path == "type" })
        assertTrue(issues.none { it.path == "actions[0].params.content" })
        assertTrue(RuleValidator.validate(rule.copy(triggers = emptyList()), registry).isEmpty())
        assertTrue(RuleValidator.validate(rule.copy(
            triggers = emptyList(),
            actions = listOf(RuleComponent("action.unknown")),
        ), registry).any { it.path == "actions[0].type" })
    }

    @Test
    fun executorPreservesStopOnFailureBehavior() {
        val registry = ComponentRegistry()
        val firstMeta = ComponentMeta("action.first", "第一步", "测试", ComponentKind.ACTION)
        val secondMeta = ComponentMeta("action.second", "第二步", "测试", ComponentKind.ACTION)
        registry.register(firstMeta)
        registry.register(secondMeta)
        val first = Action { context, _ ->
            context.variables["value"] = "kept"
            ActionOutcome(StepStatus.FAILURE, "first failed")
        }
        val second = Action { context, _ ->
            ActionOutcome(if (context.variables["value"] == "kept") StepStatus.SUCCESS else StepStatus.FAILURE, "second ran")
        }
        val executor = RuleExecutor(
            registry,
            mapOf("action.first" to first, "action.second" to second),
            CancellationCheckpoint { /* This synchronous fixture has no cancellation source. */ },
        )

        val continueResult = runSuspend {
            executor.run(RuleFactory.create(RuleType.MANUAL, "continue").copy(actions = listOf(
                RuleComponent("action.first", onError = OnError.CONTINUE), RuleComponent("action.second"),
            )), "manual")
        }
        val stopResult = runSuspend {
            executor.run(RuleFactory.create(RuleType.MANUAL, "stop").copy(actions = listOf(
                RuleComponent("action.first"), RuleComponent("action.second"),
            )), "manual")
        }

        assertEquals(StepStatus.FAILURE, continueResult.status)
        // OnError.CONTINUE now actually continues; the old expectation documented the
        // placeholder behaviour where every failure skipped the rest of the task.
        assertEquals(StepStatus.SUCCESS, continueResult.steps[1].status)
        assertEquals(StepStatus.SKIPPED, stopResult.steps[1].status)
        assertFalse(stopResult.steps[1].detail.isBlank())
    }

    @Test
    fun ruleExecutorInjectsTriggerContextVariablesIntoActionParameters() {
        val registry = ComponentRegistry()
        registry.register(ComponentMeta(
            "action.echo", "回显", "测试", ComponentKind.ACTION,
            params = listOf(ParamSpec("text", "文本", ParamType.TEXT, required = true)),
        ))
        var seen: String? = null
        val echo = Action { _, component ->
            seen = component.params["text"]?.toString()
            ActionOutcome(StepStatus.SUCCESS, "echoed")
        }
        val executor = RuleExecutor(
            registry,
            mapOf("action.echo" to echo),
            CancellationCheckpoint { /* This synchronous fixture has no cancellation source. */ },
        )
        val rule = RuleFactory.create(RuleType.MANUAL, "上下文").copy(
            actions = listOf(RuleComponent("action.echo", mapOf("text" to JsonPrimitive("%{event.battery}")))),
        )

        runSuspend { executor.run(rule, "触发：电量", mapOf("event.battery" to 15)) }

        assertEquals("\"15\"", seen)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return checkNotNull(result) { "测试协程发生了非同步挂起" }.getOrThrow()
    }
}
