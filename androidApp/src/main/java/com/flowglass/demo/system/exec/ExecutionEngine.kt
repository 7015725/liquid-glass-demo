package com.flowglass.demo.system.exec

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.PersistedAppState
import com.flowglass.demo.ui.toRuleDocument
import com.flowglass.engine.Action
import com.flowglass.engine.ActionOutcome
import com.flowglass.engine.BuiltInActions
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.CancellationCheckpoint
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.system.trigger.SystemEventCache
import com.flowglass.engine.ConditionEnvironment
import com.flowglass.engine.ExecutionLayer
import com.flowglass.engine.ParameterCodec
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleCaller
import com.flowglass.engine.RuleExecutor
import com.flowglass.engine.RunBudget
import com.flowglass.engine.VariableSink
import com.flowglass.engine.StepStatus as EngineStepStatus
import com.flowglass.engine.log.NoopTraceSink
import com.flowglass.engine.log.TraceSink
import com.flowglass.engine.log.newTraceId

enum class StepStatus { SUCCESS, FAILURE, SKIPPED }
data class StepResult(
    val itemId: Long,
    val name: String,
    val status: StepStatus,
    val detail: String,
    val durationMs: Long,
    val layer: ExecutionLayer? = null,
)
/** [output] becomes `%{...}` in later steps when the action declares an output key. */
data class TaskOutcome(
    val status: StepStatus,
    val detail: String,
    val outputs: Map<String, Any?> = emptyMap(),
)
data class RunResult(
    val cmdId: Long,
    val reason: String,
    val steps: List<StepResult>,
    val durationMs: Long,
    val ignored: Boolean = false,
    val traceId: String = "",
) {
    val ok: Boolean get() = !ignored && steps.none { it.status == StepStatus.FAILURE }
    val detail: String get() = steps.firstOrNull { it.status == StepStatus.FAILURE }?.detail
        ?: steps.joinToString("；") { "${it.name}：${it.detail}" }.ifEmpty { "没有可执行任务" }.let {
            if (it.length <= 600) it else it.take(600) + "…"
        }
}

internal fun Cmd.itemAtPath(path: List<Int>): CommandItem? {
    var node: CommandItem? = null
    var list = actions
    for (step in path) {
        if (step >= 0) {
            node = list.getOrNull(step) ?: return null
            list = node.children
        } else {
            val branch = node?.branches?.getOrNull(-step - 1) ?: return null
            list = branch.children
            node = null
        }
    }
    return node
}

private const val MAX_STORED_STEPS = 301

class ExecutionEngine(context: Context, private val executor: (suspend (Cmd, CommandItem) -> TaskOutcome)? = null) {
    private val context = context.applicationContext
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val registry = BuiltInComponents.registry()
    private val environment = AndroidConditionEnvironment(context)
    private val layerResolver = LayerResolver()

    /** Resolves a target rule by name; set by [com.flowglass.demo.ui.AppState]. */
    var ruleLookup: ((String) -> Cmd?)? = null

    /** Authoritative rule for a command (locals, id, run mode); set by AppState. */
    var ruleResolver: ((Long) -> Rule?)? = null

    /** Saved global values and their declared types; set by AppState so runs see persisted state. */
    var globalsProvider: (() -> Pair<Map<String, kotlinx.serialization.json.JsonElement>, Map<String, com.flowglass.engine.GlobalVariableMeta>>)? = null

    /** Independent async run (`wait=false`); returns immediately with an accepted/failed outcome. */
    var ruleLauncher: ((String, String, Map<String, Any?>) -> ActionOutcome)? = null

    /** 文档世代提供者；写入回调带回本次 run 启动时的世代，宿主据此丢弃跨代写入。 */
    var documentEpoch: () -> Int = { 0 }

    /** Process-wide trace sink; AppRuntime installs the SQLite store here. */
    var traceSink: TraceSink = NoopTraceSink

    /** Rule/global variable write-back; `ruleId` owns the rule-scope keys. */
    var variableWriter: ((String, Map<String, Any?>, Map<String, Any?>, Int) -> Unit)? = null

    /** Metadata for variables created or type-changed by a run. */
    var variableDefinitionWriter:
        ((String, List<com.flowglass.engine.VariableDefinition>, List<com.flowglass.engine.VariableDefinition>, Int) -> Unit)? = null

    private val caller = object : RuleCaller {
        override suspend fun call(
            ruleId: String,
            depth: Int,
            budget: RunBudget,
            variables: Map<String, Any?>,
        ): ActionOutcome {
            val target = ruleLookup?.invoke(ruleId)
                ?: return ActionOutcome(EngineStepStatus.FAILURE, "找不到指令：$ruleId")
            val nested = runInternal(target, "被「$ruleId」调用", variables, budget, depth)
            return ActionOutcome(
                if (nested.ok) EngineStepStatus.SUCCESS else EngineStepStatus.FAILURE,
                nested.detail,
            )
        }

        override fun launch(ruleId: String, depth: Int, variables: Map<String, Any?>): ActionOutcome {
            val launcher = ruleLauncher
                ?: return ActionOutcome(EngineStepStatus.FAILURE, "当前环境不支持异步调用")
            return launcher(ruleId, "被「$ruleId」调用", variables + (CHAIN_DEPTH to depth))
        }
    }

    suspend fun run(
        cmd: Cmd,
        reason: String,
        variables: Map<String, Any?> = emptyMap(),
        allowParallel: Boolean = false,
        readOnlyNames: Set<String> = emptySet(),
        traceId: String? = null,
    ): RunResult {
        currentCoroutineContext().ensureActive()
        if (!allowParallel && !active.add(cmd.id)) {
            return RunResult(cmd.id, reason, emptyList(), 0, ignored = true)
        }
        try {
            return runInternal(cmd, reason, variables, null, null, readOnlyNames, traceId)
        } finally {
            if (!allowParallel) active.remove(cmd.id)
        }
    }

    fun isRunning(cmdId: Long): Boolean = cmdId in active

    private suspend fun runInternal(
        cmd: Cmd,
        reason: String,
        variables: Map<String, Any?>,
        sharedBudget: RunBudget?,
        startDepth: Int?,
        readOnlyNames: Set<String> = emptySet(),
        traceId: String? = null,
    ): RunResult {
        val epoch = documentEpoch()
        val stored = globalsProvider?.invoke()
        val state = PersistedAppState(
            autoCmds = if (cmd.auto) listOf(cmd) else emptyList(),
            oneTaps = if (cmd.auto) emptyList() else listOf(cmd),
            logs = emptyList(),
            running = true,
            mode = 1,
            engineGlobals = stored?.first.orEmpty(),
            engineGlobalMeta = stored?.second.orEmpty(),
        )
        val document = try {
            state.toRuleDocument(includeTriggersAndConditions = false)
        } catch (invalid: IllegalArgumentException) {
            return RunResult(cmd.id, reason, listOf(StepResult(cmd.id, "参数校验", StepStatus.FAILURE,
                "参数无效：${invalid.message.orEmpty()}", 0)), 0)
        }
        val built = document.rules.single()
        // The UI model carries actions only; locals/id/run mode live in the stored rule.
        val authoritative = ruleResolver?.invoke(cmd.id)
        val rule = if (authoritative == null) built else authoritative.copy(
            name = cmd.title,
            description = cmd.desc,
            enabled = cmd.enabled,
            triggers = emptyList(),
            conditions = emptyList(),
            actions = built.actions,
        )
        val depth = startDepth ?: ((variables[CHAIN_DEPTH] as? Number)?.toInt() ?: 0)
        val initial = variables - CHAIN_DEPTH
        val startedNanos = System.nanoTime()
        val log = RuleExecutor(
            registry,
            handlersFor(cmd),
            CancellationCheckpoint { currentCoroutineContext().ensureActive() },
            caller = caller,
            sink = sinkFor(rule, epoch),
            environment = environment,
            conditionLayerSelector = { meta -> layerResolver.select(meta, com.flowglass.engine.ComponentKind.CONDITION)?.layer },
            globalVariableMeta = document.globalMeta,
            // Cooperative: delay() lets a stop cancel the poll immediately instead of blocking a
            // Dispatchers.Default thread for up to the 1 hour timeout.
            sleeper = { kotlinx.coroutines.delay(it) },
            trace = traceSink,
            traceId = traceId ?: newTraceId(),
        ).run(rule, reason, initial, document.globals, sharedBudget ?: RunBudget(), depth, readOnlyNames)
        val steps = log.steps.map { step ->
            val item = cmd.itemAtPath(step.path)
            StepResult(
                itemId = item?.id ?: 0L,
                name = item?.name ?: step.componentType,
                status = when (step.status) {
                    EngineStepStatus.SUCCESS -> StepStatus.SUCCESS
                    EngineStepStatus.FAILURE -> StepStatus.FAILURE
                    EngineStepStatus.SKIPPED -> StepStatus.SKIPPED
                },
                detail = step.detail,
                durationMs = step.durationMs,
                layer = step.layer,
            )
        }
        val capped = capSteps(cmd, steps)
        // A budget/stop failure has no failing step of its own: surface it so the log is not green.
        val failureStep = if (log.status == EngineStepStatus.FAILURE && steps.none { it.status == StepStatus.FAILURE }) {
            listOf(StepResult(cmd.id, "运行结果", StepStatus.FAILURE, log.detail.ifBlank { "运行失败" }, 0))
        } else emptyList()
        return RunResult(
            cmdId = cmd.id,
            reason = reason,
            steps = capped + failureStep,
            durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
            traceId = log.id,
        )
    }

    /**
     * Keep a run log bounded: first 250, last 50, one marker and any failure from the middle.
     * The engine still counts every step; this only trims what the UI stores per run.
     */
    internal fun capSteps(cmd: Cmd, steps: List<StepResult>): List<StepResult> {
        if (steps.size <= MAX_STORED_STEPS) return steps
        val headLimit = 250
        val tailCount = 50
        val tailStart = steps.size - tailCount
        val tail = steps.subList(tailStart, steps.size)
        val failureBudget = MAX_STORED_STEPS - 1 - tailCount
        // Keep the last failures from the middle, then fill the rest with non-failing head steps.
        val failureIndexes = (0 until tailStart).filter { steps[it].status == StepStatus.FAILURE }
            .takeLast(failureBudget)
        val failureSet = failureIndexes.toHashSet()
        val headBudget = (MAX_STORED_STEPS - 1 - tailCount - failureIndexes.size).coerceAtLeast(0)
        val head = (0 until headLimit).filter { it !in failureSet }.take(headBudget).map { steps[it] }
        val failures = failureIndexes.map { steps[it] }
        val omitted = steps.size - head.size - failures.size - tail.size
        val marker = StepResult(cmd.id, "已省略 $omitted 个步骤", StepStatus.SKIPPED, "中间步骤仅保留失败项", 0)
        return head + failures + marker + tail
    }

    private fun sinkFor(targetRule: Rule, epoch: Int): VariableSink = object : VariableSink {
        override fun persist(ruleWrites: Map<String, Any?>, globalWrites: Map<String, Any?>) {
            if (ruleWrites.isNotEmpty() || globalWrites.isNotEmpty()) {
                variableWriter?.invoke(targetRule.id, ruleWrites, globalWrites, epoch)
            }
        }

        override fun persistDefinitions(
            rule: List<com.flowglass.engine.VariableDefinition>,
            global: List<com.flowglass.engine.VariableDefinition>,
        ) {
            if (rule.isNotEmpty() || global.isNotEmpty()) {
                variableDefinitionWriter?.invoke(targetRule.id, rule, global, epoch)
            }
        }
    }

    private fun handlersFor(cmd: Cmd): Map<String, Action> = BuiltInActions.metadata.associate { meta ->
        meta.typeId to Action { runContext, component ->
            // The executor reports the step it is running, so parameter expansion (which
            // replaces the component instance) cannot break the item mapping.
            val source = cmd.itemAtPath(runContext.currentPath)
            val parameter = ParameterCodec.formatComponent(meta, component.params)
            val item = source?.copy(name = meta.name, parameter = parameter) ?: CommandItem(
                id = runContext.currentStep.toLong(),
                name = meta.name,
                parameter = parameter,
                ic = cmd.ic,
                typeId = component.type,
            )
            var selectedLayer: ExecutionLayer? = null
            val outcome = try {
                if (executor == null) {
                    // Refresh only when this component declares Root; normal-only actions never
                    // touch su. `request = false` reuses a remembered grant and never prompts.
                    if (meta.supports.any { it.layer == ExecutionLayer.ROOT }) {
                        // Do not let a passive root probe overwrite a READY LSPosed snapshot.
                        // isAlive() is the same predicate used by the layer settings page.
                        LayerStatus.refresh(
                            requestRoot = false,
                            systemHookReady = SystemHookSettings.isAlive(context),
                        )
                    }
                    val selection = layerResolver.select(meta)
                    if (selection == null) {
                        TaskOutcome(StepStatus.FAILURE, "缺少执行层：${layerResolver.unavailableReason(meta)}")
                    } else {
                        selectedLayer = selection.layer
                        ActionLayers.run(context, cmd, item, selection, runContext.scriptVariables)
                    }
                } else {
                    // Test seam: injected executors retain their previous normal-layer behavior.
                    selectedLayer = ExecutionLayer.NORMAL
                    executor.invoke(cmd, item)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                TaskOutcome(StepStatus.FAILURE, failure.message?.take(500) ?: "任务执行失败")
            }
            ActionOutcome(
                status = when (outcome.status) {
                    StepStatus.SUCCESS -> EngineStepStatus.SUCCESS
                    StepStatus.FAILURE -> EngineStepStatus.FAILURE
                    StepStatus.SKIPPED -> EngineStepStatus.SKIPPED
                },
                detail = outcome.detail,
                outputs = outcome.outputs,
                layer = selectedLayer,
            )
        }
    }

    private companion object {
        const val CHAIN_DEPTH = "chain.depth"
    }
}

/** Android-backed condition inputs for blocks evaluated inside a run. */
private class AndroidConditionEnvironment(private val context: Context) : ConditionEnvironment {
    override fun zoneOffsetMinutes(atEpochMs: Long): Int =
        java.util.TimeZone.getDefault().getOffset(atEpochMs) / 60_000

    override fun batteryPercent(): Int? = runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }.getOrNull()

    override fun isCharging(): Boolean = runCatching {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    override fun isScreenOn(): Boolean = runCatching {
        val manager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        manager.isInteractive
    }.getOrDefault(true)

    override fun appProcessRunning(packageName: String, userId: Int): Boolean? =
        SystemEventCache.isProcessRunning(packageName, userId)

    override fun isDndOn(): Boolean? = runCatching {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
            ?: return null
        val filter = manager.currentInterruptionFilter
        if (filter == android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN) null
        else filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrNull()

    override fun networkType(): com.flowglass.engine.trigger.NetworkType = runCatching {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return com.flowglass.engine.trigger.NetworkType.NONE
        val network = connectivity.activeNetwork ?: return com.flowglass.engine.trigger.NetworkType.NONE
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return com.flowglass.engine.trigger.NetworkType.NONE
        when {
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ->
                com.flowglass.engine.trigger.NetworkType.WIFI
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ->
                com.flowglass.engine.trigger.NetworkType.MOBILE
            else -> com.flowglass.engine.trigger.NetworkType.NONE
        }
    }.getOrDefault(com.flowglass.engine.trigger.NetworkType.NONE)

    override fun fileExists(path: String, layer: com.flowglass.engine.ExecutionLayer?): Boolean? =
        ConditionFileAccess.exists(path, layer)
}
