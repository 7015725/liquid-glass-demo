package com.flowglass.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

@Serializable
enum class StepStatus { SUCCESS, FAILURE, SKIPPED }

@Serializable
data class StepResult(
    val componentType: String,
    val status: StepStatus,
    val detail: String,
    val durationMs: Long,
    /**
     * Position inside the task tree. Non-negative numbers index a sequence; a negative number
     * `-(branch + 1)` selects an if else-if/else branch before the following indices. The host
     * maps an executed step back to the node that produced it.
     */
    val path: List<Int> = emptyList(),
)

@Serializable
data class RunLog(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val reason: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val status: StepStatus,
    val detail: String,
    val steps: List<StepResult>,
)

data class RunContext(
    val rule: Rule,
    val reason: String,
    val variables: MutableMap<String, Any?> = linkedMapOf(),
    /** 当前正在执行的步骤下标（从 0 开始）；-1 表示未开始。 */
    var currentStep: Int = -1,
    /** 当前步骤在任务树中的路径，与 [StepResult.path] 同一套编码。 */
    var currentPath: List<Int> = emptyList(),
)

/** [output] is published to the run context under the first key of `ComponentMeta.outputs`. */
data class ActionOutcome(val status: StepStatus, val detail: String, val output: String? = null)

interface Trigger {
    val meta: ComponentMeta
    fun start(emit: (Map<String, String>) -> Unit)
    fun stop()
}

fun interface Condition {
    suspend fun check(context: RunContext, component: RuleComponent): Boolean
}

fun interface Action {
    suspend fun run(context: RunContext, component: RuleComponent): ActionOutcome
}

fun interface Clock {
    fun nowEpochMs(): Long
}

/** The host must throw CancellationException when the current execution is cancelled. */
fun interface CancellationCheckpoint {
    suspend fun ensureActive()
}

/** Nested execution of another rule; implemented by the host, which owns the rule list. */
interface RuleCaller {
    /** `wait = true`: shares [budget] and runs inside the parent's coroutine scope. */
    suspend fun call(ruleId: String, depth: Int, budget: RunBudget, variables: Map<String, Any?>): ActionOutcome

    /** `wait = false`: starts an independent run; must not throw when the parent is cancelled. */
    fun launch(ruleId: String, depth: Int, variables: Map<String, Any?>): ActionOutcome
}

/** Persists rule/global variable changes when a run ends normally (success or failure). */
fun interface VariableSink {
    fun persist(rule: Map<String, Any?>, global: Map<String, Any?>)

    companion object {
        val Noop = VariableSink { _, _ -> }
    }
}

/** Step counter shared by a run and its synchronous child runs. */
class RunBudget(val maxSteps: Int = 10_000) {
    var steps: Int = 0
        private set

    fun consume() {
        steps++
        if (steps > maxSteps) throw BudgetExceeded(maxSteps)
    }
}

class BudgetExceeded(val maxSteps: Int) : RuntimeException("超过单次运行 $maxSteps 步")

private class BreakSignal : RuntimeException()
private class StopSignal(val success: Boolean, val text: String) : RuntimeException()

private class SequenceOutcome(val status: StepStatus, val detail: String)

private const val LAST_DETAIL = "last"
private const val MAX_LOOP = 1000
private const val MAX_DEPTH = 5

/**
 * Scoped variable table.
 *
 * Bare `%{x}` keeps resolving to the run scope, so `%{last}` and action outputs behave exactly
 * as before. `event.` / `rule.` / `global.` prefixes opt into the other scopes.
 */
internal class RunScopes(
    rule: Rule,
    initial: Map<String, Any?>,
    globals: Map<String, Any?>,
) {
    val event: MutableMap<String, Any?> = linkedMapOf()
    val run: MutableMap<String, Any?> = linkedMapOf()
    val ruleVars: MutableMap<String, Any?> = linkedMapOf()
    val global: MutableMap<String, Any?> = LinkedHashMap(globals)
    private val ruleDirty = linkedSetOf<String>()
    private val globalDirty = linkedSetOf<String>()

    init {
        for (local in rule.locals) ruleVars[local.name] = local.initial.toPlainValue()
        for ((key, value) in initial) {
            if (key.startsWith("event.")) event[key.removePrefix("event.")] = value else run[key] = value
        }
    }

    fun flattened(): Map<String, Any?> = buildMap {
        event.forEach { (k, v) -> put("event.$k", v) }
        ruleVars.forEach { (k, v) -> put("rule.$k", v) }
        global.forEach { (k, v) -> put("global.$k", v) }
        putAll(run)
        run.forEach { (k, v) -> put("run.$k", v) }
    }

    fun ruleWrites(): Map<String, Any?> = ruleDirty.associateWith { ruleVars[it] }
    fun globalWrites(): Map<String, Any?> = globalDirty.associateWith { global[it] }

    /** Applies [op] and returns the stored value, so the step log shows the result. */
    fun set(name: String, value: String, op: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "变量名不能为空" }
        require(Regex("[A-Za-z0-9_.-]+").matches(trimmed)) { "变量名只能包含字母、数字、_ . -" }
        val (scope, key) = when {
            trimmed.startsWith("global.") -> global to trimmed.removePrefix("global.")
            trimmed.startsWith("rule.") -> ruleVars to trimmed.removePrefix("rule.")
            trimmed.startsWith("run.") -> run to trimmed.removePrefix("run.")
            else -> run to trimmed
        }
        require(key.isNotEmpty()) { "变量名不能为空" }
        val next = when (op) {
            "set" -> value
            "append" -> (scope[key]?.toString().orEmpty()) + value
            "add", "sub", "mul", "div" -> {
                val left = scope[key]?.toString()?.trim()?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("变量 $key 不是数字")
                val right = value.trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("值不是数字")
                if (op == "div" && right == 0.0) throw IllegalArgumentException("不能除以零")
                val result = when (op) {
                    "add" -> left + right
                    "sub" -> left - right
                    "mul" -> left * right
                    else -> left / right
                }
                if (result == result.toLong().toDouble()) result.toLong().toString() else result.toString()
            }
            else -> throw IllegalArgumentException("未知运算：$op")
        }
        scope[key] = next
        if (scope === ruleVars) ruleDirty += key
        if (scope === global) globalDirty += key
        return next.toString()
    }
}

private fun kotlinx.serialization.json.JsonElement.toPlainValue(): Any? = when (this) {
    is JsonPrimitive -> if (isString) content else content.toDoubleOrNull() ?: content
    is JsonObject, is JsonArray -> toString()
}

class RuleExecutor(
    private val registry: ComponentRegistry,
    private val actions: Map<String, Action>,
    private val cancellationCheckpoint: CancellationCheckpoint,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val caller: RuleCaller? = null,
    private val sink: VariableSink = VariableSink.Noop,
    private val maxSteps: Int = 10_000,
    private val environment: ConditionEnvironment? = null,
    private val sleeper: suspend (Long) -> Unit = { Thread.sleep(it) },
) {
    suspend fun run(
        rule: Rule,
        reason: String,
        initialVariables: Map<String, Any?> = emptyMap(),
        globals: Map<String, Any?> = emptyMap(),
        budget: RunBudget = RunBudget(maxSteps),
        startDepth: Int = 0,
    ): RunLog {
        cancellationCheckpoint.ensureActive()
        val start = clock.nowEpochMs()
        val monotonicStart = kotlin.time.TimeSource.Monotonic.markNow()
        val steps = mutableListOf<StepResult>()
        val context = RunContext(rule, reason)
        val scopes = RunScopes(rule, initialVariables, globals)
        var budgetExceeded: BudgetExceeded? = null
        var stopped: StopSignal? = null

        try {
            execSequence(rule.actions, emptyList(), startDepth, context, scopes, budget, steps)
        } catch (stop: StopSignal) {
            stopped = stop
        } catch (exceeded: BudgetExceeded) {
            budgetExceeded = exceeded
        } catch (cancelled: CancellationException) {
            // A manual stop must not persist half-finished variables or fabricate a result.
            throw cancelled
        }

        val failed = steps.firstOrNull { it.status == StepStatus.FAILURE }
        val status = when {
            budgetExceeded != null -> StepStatus.FAILURE
            stopped != null && !stopped.success -> StepStatus.FAILURE
            failed != null -> StepStatus.FAILURE
            steps.isEmpty() || steps.all { it.status == StepStatus.SKIPPED } ->
                if (stopped?.success == true) StepStatus.SUCCESS else StepStatus.SKIPPED
            else -> StepStatus.SUCCESS
        }
        val detail = when {
            budgetExceeded != null -> "超过单次运行 ${budgetExceeded.maxSteps} 步"
            stopped != null && stopped.text.isNotBlank() -> stopped.text
            stopped != null && !stopped.success -> "任务按失败结束"
            failed != null -> failed.detail
            else -> steps.joinToString("；") { it.detail }.ifEmpty { "没有可执行动作" }
                .let { if (it.length <= 600) it else it.take(600) + "…" }
        }
        val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            monotonicStart.elapsedNow().inWholeNanoseconds.coerceAtLeast(0),
        )
        // Success and failure both persist variables; cancellation already returned above.
        sink.persist(scopes.ruleWrites(), scopes.globalWrites())
        return RunLog(
            id = newIdString(),
            ruleId = rule.id,
            ruleName = rule.name,
            reason = reason,
            startedAtEpochMs = start,
            durationMs = elapsed,
            status = status,
            detail = detail,
            steps = steps,
        )
    }

    private suspend fun execSequence(
        nodes: List<RuleComponent>,
        path: List<Int>,
        depth: Int,
        context: RunContext,
        scopes: RunScopes,
        budget: RunBudget,
        steps: MutableList<StepResult>,
    ): SequenceOutcome {
        for ((index, node) in nodes.withIndex()) {
            budget.consume()
            cancellationCheckpoint.ensureActive()
            val outcome = execNode(node, path + index, depth, context, scopes, budget, steps)
            if (outcome.status == StepStatus.FAILURE && node.onError != OnError.CONTINUE) {
                for (rest in index + 1 until nodes.size) {
                    steps += StepResult(nodes[rest].type, StepStatus.SKIPPED, "前序动作失败，未执行", 0, path + rest)
                }
                return outcome
            }
        }
        return SequenceOutcome(StepStatus.SUCCESS, "")
    }

    private suspend fun execNode(
        node: RuleComponent,
        path: List<Int>,
        depth: Int,
        context: RunContext,
        scopes: RunScopes,
        budget: RunBudget,
        steps: MutableList<StepResult>,
    ): SequenceOutcome {
        val meta = registry.find(node.type)
        if (meta == null || meta.kind != ComponentKind.ACTION) {
            steps += StepResult(node.type, StepStatus.SKIPPED, "暂不支持该动作", 0, path)
            return SequenceOutcome(StepStatus.SKIPPED, "暂不支持该动作")
        }
        context.currentPath = path
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        fun record(status: StepStatus, detail: String) {
            val ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(started.elapsedNow().inWholeNanoseconds.coerceAtLeast(0))
            // A growing variable (e.g. 10k appends) must not turn one step detail into megabytes.
            val text = if (detail.length <= 200) detail else detail.take(200) + "…"
            steps += StepResult(node.type, status, text, ms, path)
        }
        val block = meta.block ?: return runPlainStep(node, meta, path, context, scopes, steps)

        return when (block) {
            BlockKind.IF -> runIf(node, path, depth, context, scopes, budget, steps, ::record)
            BlockKind.LOOP -> runLoop(node, path, depth, context, scopes, budget, steps, ::record)
            BlockKind.WAIT -> runWait(node, scopes, ::record)
            BlockKind.BREAK -> throw BreakSignal()
            BlockKind.STOP -> {
                val success = node.params["status"]?.stringValue() != "failure"
                val message = ParamTemplate.expand(node.params["message"]?.stringValue().orEmpty(), scopes.flattened())
                    .ifBlank { if (success) "任务提前结束" else "任务按失败结束" }
                record(if (success) StepStatus.SUCCESS else StepStatus.FAILURE, message)
                throw StopSignal(success, message)
            }
            BlockKind.SET_VAR -> runSetVar(node, scopes, ::record)
            BlockKind.RUN_RULE -> runChain(node, depth, budget, scopes, ::record)
        }
    }

    private suspend fun runPlainStep(
        node: RuleComponent,
        meta: ComponentMeta,
        path: List<Int>,
        context: RunContext,
        scopes: RunScopes,
        steps: MutableList<StepResult>,
    ): SequenceOutcome {
        val action = actions[node.type]
        if (action == null) {
            steps += StepResult(node.type, StepStatus.SKIPPED, "暂不支持该动作", 0, path)
            return SequenceOutcome(StepStatus.SKIPPED, "暂不支持该动作")
        }
        val variables = scopes.flattened()
        val expanded = ParamTemplate.expand(node.params, meta.params, variables)
        val resolved = if (expanded == node.params) node else node.copy(params = expanded)
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        // Exceptions propagate: the host owns the "internal error" wording and its log row.
        // Only a FAILURE outcome participates in RETRY.
        suspend fun attempt(): ActionOutcome = action.run(context, resolved)
        var result = attempt()
        if (result.status == StepStatus.FAILURE && node.onError == OnError.RETRY) result = attempt()
        cancellationCheckpoint.ensureActive()
        val duration = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(started.elapsedNow().inWholeNanoseconds.coerceAtLeast(0))
        steps += StepResult(node.type, result.status, result.detail, duration, path)
        scopes.run[LAST_DETAIL] = result.detail
        val outputKey = meta.outputs.firstOrNull()
        if (outputKey != null && result.output != null) scopes.run[outputKey] = result.output
        return SequenceOutcome(result.status, result.detail)
    }

    private suspend fun runIf(
        node: RuleComponent,
        path: List<Int>,
        depth: Int,
        context: RunContext,
        scopes: RunScopes,
        budget: RunBudget,
        steps: MutableList<StepResult>,
        record: (StepStatus, String) -> Unit,
    ): SequenceOutcome {
        val variables = scopes.flattened()
        fun passes(conditions: List<RuleComponent>) =
            conditions.isNotEmpty() && conditions.all { evaluate(it, variables) }
        val branchIndex: Int
        val children: List<RuleComponent>
        when {
            passes(node.conditions) -> { branchIndex = -1; children = node.children }
            else -> {
                val elseIf = node.branches.indexOfFirst { it.conditions.isNotEmpty() && passes(it.conditions) }
                if (elseIf >= 0) {
                    branchIndex = elseIf; children = node.branches[elseIf].children
                } else {
                    val elseBranch = node.branches.indexOfFirst { it.conditions.isEmpty() }
                    if (elseBranch < 0) {
                        record(StepStatus.SKIPPED, "没有命中的分支")
                        return SequenceOutcome(StepStatus.SKIPPED, "没有命中的分支")
                    }
                    branchIndex = elseBranch; children = node.branches[elseBranch].children
                }
            }
        }
        // Then branch keeps plain indices; only else-if/else need a marker, so no ambiguity.
        val childPath = if (branchIndex < 0) path else path + (-(branchIndex + 1))
        val outcome = execSequence(children, childPath, depth, context, scopes, budget, steps)
        val picked = when {
            branchIndex < 0 -> "条件命中"
            node.branches.getOrNull(branchIndex)?.conditions?.isNotEmpty() == true -> "否则如果命中"
            else -> "进入否则"
        }
        record(outcome.status, if (outcome.status == StepStatus.FAILURE) outcome.detail else picked)
        return outcome
    }

    private suspend fun runLoop(
        node: RuleComponent,
        path: List<Int>,
        depth: Int,
        context: RunContext,
        scopes: RunScopes,
        budget: RunBudget,
        steps: MutableList<StepResult>,
        record: (StepStatus, String) -> Unit,
    ): SequenceOutcome {
        val whileMode = node.params["mode"]?.stringValue() == "while"
        val requested = node.params["times"]?.stringValue()?.toIntOrNull() ?: 3
        val limit = if (whileMode) MAX_LOOP else requested.coerceIn(0, MAX_LOOP)
        var iterations = 0
        var failure: SequenceOutcome? = null
        try {
            while (iterations < limit) {
                if (whileMode) {
                    val variables = scopes.flattened()
                    val pass = node.conditions.isNotEmpty() && node.conditions.all { evaluate(it, variables) }
                    if (!pass) break
                }
                iterations++
                // The iteration index is runtime state, not part of the tree: step paths stay
                // [block, child] so the host can map every iteration back to the same node.
                val outcome = execSequence(node.children, path, depth, context, scopes, budget, steps)
                if (outcome.status == StepStatus.FAILURE && node.onError != OnError.CONTINUE) {
                    failure = outcome
                    break
                }
            }
        } catch (broken: BreakSignal) {
            record(StepStatus.SUCCESS, "已跳出循环（第 $iterations 次）")
            return SequenceOutcome(StepStatus.SUCCESS, "已跳出循环")
        }
        failure?.let {
            record(StepStatus.FAILURE, it.detail)
            return it
        }
        if (iterations == 0) {
            record(StepStatus.SKIPPED, "循环体未执行")
            return SequenceOutcome(StepStatus.SKIPPED, "循环体未执行")
        }
        record(StepStatus.SUCCESS, "循环 $iterations 次")
        return SequenceOutcome(StepStatus.SUCCESS, "循环 $iterations 次")
    }

    private suspend fun runWait(
        node: RuleComponent,
        scopes: RunScopes,
        record: (StepStatus, String) -> Unit,
    ): SequenceOutcome {
        if (node.conditions.isEmpty()) {
            record(StepStatus.FAILURE, "等待缺少条件")
            return SequenceOutcome(StepStatus.FAILURE, "等待缺少条件")
        }
        val timeout = (node.params["timeout"]?.stringValue()?.toLongOrNull() ?: 30_000L).coerceIn(1_000L, 3_600_000L)
        val poll = (node.params["poll"]?.stringValue()?.toLongOrNull() ?: 1_000L).coerceIn(250L, 5_000L)
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        while (true) {
            cancellationCheckpoint.ensureActive()
            val variables = scopes.flattened()
            if (node.conditions.all { evaluate(it, variables) }) {
                record(StepStatus.SUCCESS, "条件已成立")
                return SequenceOutcome(StepStatus.SUCCESS, "条件已成立")
            }
            val elapsed = started.elapsedNow().inWholeMilliseconds
            if (elapsed >= timeout) {
                val names = node.conditions.joinToString("、") { it.type.substringAfter('.') }
                record(StepStatus.FAILURE, "等待超时：$names")
                return SequenceOutcome(StepStatus.FAILURE, "等待超时：$names")
            }
            sleeper(minOf(poll, timeout - elapsed).coerceAtLeast(1))
        }
    }

    private fun runSetVar(
        node: RuleComponent,
        scopes: RunScopes,
        record: (StepStatus, String) -> Unit,
    ): SequenceOutcome {
        val name = node.params["name"]?.stringValue().orEmpty()
        val value = ParamTemplate.expand(node.params["value"]?.stringValue().orEmpty(), scopes.flattened())
        val op = node.params["op"]?.stringValue() ?: "set"
        return try {
            val stored = scopes.set(name, value, op)
            record(StepStatus.SUCCESS, "$name = ${stored.ifEmpty { "(空)" }}")
            SequenceOutcome(StepStatus.SUCCESS, "$name = $stored")
        } catch (invalid: IllegalArgumentException) {
            record(StepStatus.FAILURE, invalid.message ?: "变量赋值失败")
            SequenceOutcome(StepStatus.FAILURE, invalid.message ?: "变量赋值失败")
        }
    }

    private suspend fun runChain(
        node: RuleComponent,
        depth: Int,
        budget: RunBudget,
        scopes: RunScopes,
        record: (StepStatus, String) -> Unit,
    ): SequenceOutcome {
        val target = node.params["rule"]?.stringValue().orEmpty().trim()
        if (target.isEmpty()) {
            record(StepStatus.FAILURE, "未选择目标指令")
            return SequenceOutcome(StepStatus.FAILURE, "未选择目标指令")
        }
        if (depth + 1 > MAX_DEPTH) {
            record(StepStatus.FAILURE, "调用层级超过 $MAX_DEPTH 层")
            return SequenceOutcome(StepStatus.FAILURE, "调用层级超过 $MAX_DEPTH 层")
        }
        val host = caller ?: run {
            record(StepStatus.FAILURE, "当前执行器不支持调用指令")
            return SequenceOutcome(StepStatus.FAILURE, "当前执行器不支持调用指令")
        }
        val wait = node.params["wait"]?.stringValue() != "false"
        val outcome = if (wait) {
            host.call(target, depth + 1, budget, scopes.flattened())
        } else {
            host.launch(target, depth + 1, scopes.flattened())
        }
        record(outcome.status, outcome.detail)
        return SequenceOutcome(outcome.status, outcome.detail)
    }

    private fun evaluate(condition: RuleComponent, variables: Map<String, Any?>): Boolean =
        Conditions.evaluate(
            condition,
            variables,
            environment ?: DefaultEnvironment,
            clock.nowEpochMs(),
        )

    private companion object {
        /**
         * Fallback for host tests: real time zone, no battery/screen information. The app injects
         * the Android environment so battery/screen conditions keep working inside blocks.
         */
        val DefaultEnvironment = object : ConditionEnvironment {
            override fun zoneOffsetMinutes(atEpochMs: Long): Int =
                (java.util.TimeZone.getDefault().getOffset(atEpochMs) / 60_000).toInt()
            override fun batteryPercent(): Int? = null
            override fun isCharging(): Boolean = false
            override fun isScreenOn(): Boolean = true
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.stringValue(): String? = when (this) {
    is JsonObject, is JsonArray -> null
    else -> jsonPrimitive.content
}
