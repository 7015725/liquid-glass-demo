package com.flowglass.engine.trigger

import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.Clock
import com.flowglass.engine.ExecutionLayer
import com.flowglass.engine.MatchMode
import com.flowglass.engine.OutputCatalog
import com.flowglass.engine.OutputValues
import com.flowglass.engine.VariableType
import com.flowglass.engine.methodhook.MethodHookVariables
import com.flowglass.engine.log.NoopTraceSink
import com.flowglass.engine.log.TraceEvent
import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceLevel
import com.flowglass.engine.log.TraceRedaction
import com.flowglass.engine.log.TraceSink
import com.flowglass.engine.log.TraceStatus
import com.flowglass.engine.log.newTraceId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Read-only state queried at the moment a trigger fires; nothing here is cached by the rule. */
enum class NetworkType { WIFI, MOBILE, NONE }

interface TriggerEnvironment {
    fun zoneOffsetMinutes(atEpochMs: Long): Int
    fun batteryPercent(): Int?
    fun isCharging(): Boolean
    fun isScreenOn(): Boolean
    fun isDndOn(): Boolean? = null
    fun networkType(): NetworkType = NetworkType.NONE

    /** SSID of the current Wi-Fi connection, or null when unknown/not connected. */
    fun wifiSsid(): String? = null
    fun appProcessRunning(packageName: String, userId: Int): Boolean? = null
    fun fileExists(path: String): Boolean? = null

    /**
     * Persisted rule-local and global values keyed by name. Trigger conditions are evaluated outside
     * the rule executor, so the host has to feed them here or `变量判断` treats saved state as unset.
     */
    fun persistedVariables(ruleId: String): Map<String, Any?> = emptyMap()
}

interface TriggerExecutor {
    fun isRunning(rule: TriggerRule): Boolean

    /** Number of active runs for the rule; parallel mode uses this to enforce its cap. */
    fun activeCount(rule: TriggerRule): Int = if (isRunning(rule)) 1 else 0

    /** Cancels active runs for RESTART. Hosts that cannot cancel synchronously may keep the default. */
    fun cancel(rule: TriggerRule) {}

    /** Starts the rule and returns false when the repeat policy dropped this trigger. */
    fun execute(
        rule: TriggerRule,
        binding: TriggerBinding,
        reason: String,
        variables: Map<String, Any?>,
        readOnlyNames: Set<String> = emptySet(),
    ): Boolean

    /**
     * Trace-aware entry point. The default delegates to [execute] so existing hosts and tests
     * keep working; TriggerHost overrides it to pass the id into the rule run.
     */
    fun executeTraced(
        rule: TriggerRule,
        binding: TriggerBinding,
        reason: String,
        variables: Map<String, Any?>,
        readOnlyNames: Set<String>,
        traceId: String,
    ): Boolean = execute(rule, binding, reason, variables, readOnlyNames)
}

fun interface TriggerSkipRecorder {
    fun record(rule: TriggerRule, reason: String, detail: String, atEpochMs: Long)
}

data class TriggerExecution(
    val rule: TriggerRule,
    val binding: TriggerBinding,
    val reason: String,
    val variables: Map<String, Any?>,
    val readOnlyNames: Set<String> = emptySet(),
    val traceId: String = "",
)

data class TriggerSkip(
    val rule: TriggerRule,
    val reason: String,
    val detail: String,
)

data class DispatchOutcome(
    val executions: List<TriggerExecution> = emptyList(),
    val skips: List<TriggerSkip> = emptyList(),
    val debounced: Int = 0,
    val dropped: Int = 0,
    val queued: Int = 0,
)

data class TriggerConditionEvaluation(
    val component: TriggerComponent,
    val passed: Boolean,
    val detail: String,
)

data class ConditionOutcome(
    val passed: Boolean,
    val failed: List<String> = emptyList(),
    val evaluations: List<TriggerConditionEvaluation> = emptyList(),
) {
    val detail: String get() = "条件不满足：${failed.joinToString("、")}"
}

object TriggerConditions {
    fun evaluate(
        conditions: List<TriggerComponent>,
        environment: TriggerEnvironment,
        nowEpochMs: Long,
        zoneOffsetMinutes: Int,
        variables: Map<String, Any?> = emptyMap(),
    ): ConditionOutcome {
        if (conditions.isEmpty()) return ConditionOutcome(true)
        val conditionEnvironment = object : com.flowglass.engine.ConditionEnvironment {
            override fun zoneOffsetMinutes(atEpochMs: Long): Int = environment.zoneOffsetMinutes(atEpochMs)
            override fun batteryPercent(): Int? = environment.batteryPercent()
            override fun isCharging(): Boolean = environment.isCharging()
            override fun isScreenOn(): Boolean = environment.isScreenOn()
            override fun isDndOn(): Boolean? = environment.isDndOn()
            override fun networkType(): NetworkType = environment.networkType()
            override fun appProcessRunning(packageName: String, userId: Int): Boolean? =
                environment.appProcessRunning(packageName, userId)
            override fun fileExists(path: String, layer: ExecutionLayer?): Boolean? =
                environment.fileExists(path)
        }
        val evaluations = conditions.map { condition ->
            val result = com.flowglass.engine.Conditions.evaluateDetailed(
                com.flowglass.engine.RuleComponent(condition.typeId, condition.params),
                variables,
                conditionEnvironment,
                nowEpochMs,
            )
            TriggerConditionEvaluation(condition, result.passed, result.detail)
        }
        val failed = evaluations.filterNot { it.passed }.map { it.component.name }
        return ConditionOutcome(failed.isEmpty(), failed, evaluations)
    }
}

private const val MAX_PARALLEL_RUNS = 3
private const val MAX_QUEUED_RUNS = 10

/**
 * Event -> trigger match -> debounce -> condition -> execute.
 *
 * `1b-1` deliberately implements only the "any trigger" and "all conditions" modes and the
 * "drop" repeat policy; the stored match modes are evaluated in `1c`.
 */
class TriggerDispatcher(
    private val clock: Clock,
    private val environment: TriggerEnvironment,
    private val executor: TriggerExecutor,
    private val skipRecorder: TriggerSkipRecorder,
    private val debounceMs: Long = 2_000,
    private val skipLogIntervalMs: Long = 60_000,
    private val trace: TraceSink = NoopTraceSink,
    private val includeRawEvents: () -> Boolean = { true },
) {
    private val lastFired = mutableMapOf<String, Long>()
    private val lastSkipLog = mutableMapOf<String, Long>()
    private val states = TriggerStateStore()
    private val lastEvents = mutableMapOf<String, Long>()
    private val queues = mutableMapOf<String, ArrayDeque<TriggerExecution>>()

    fun dispatch(event: TriggerEvent, rules: List<TriggerRule>): DispatchOutcome {
        val now = clock.nowEpochMs()
        val traceId = event.traceId.ifBlank { newTraceId() }
        trace.record(
            TraceEvent(
                traceId = traceId,
                atEpochMs = now,
                kind = TraceKind.EVENT,
                status = TraceStatus.INFO,
                source = event.source.name,
                eventKind = event.kind,
                message = "收到 ${event.kind}",
                eventData = TraceRedaction.event(event.data, includeRaw = includeRawEvents()),
            ),
        )
        val offset = environment.zoneOffsetMinutes(now)
        val enabled = rules.filter { it.enabled }
        val allBindings = enabled.flatMap { TriggerPlan.bindings(it) }
        val keys = allBindings.map { it.key }.toSet()
        val debounceKeys = keys + enabled.map { "${it.id}#all" }
        states.removeMissing(keys)
        lastFired.keys.retainAll(debounceKeys)
        lastSkipLog.keys.retainAll(enabled.map { it.id }.toSet())
        lastEvents.keys.retainAll(keys)
        queues.keys.retainAll(enabled.map { it.id }.toSet())

        val bindings = allBindings.filter { it.source == event.source }
        val matched = bindings.filter {
            TriggerMatcher.matches(it, event, states, environment.batteryPercent())
        }
        matched.forEach { lastEvents[it.key] = now }

        val executions = mutableListOf<TriggerExecution>()
        val skips = mutableListOf<TriggerSkip>()
        var debounced = 0
        var dropped = 0
        var queued = 0

        for (rule in enabled) {
            val ruleBindings = allBindings.filter { it.rule.id == rule.id }
            val eventBindings = ruleBindings.filter { it.source == event.source }
            if (eventBindings.isEmpty()) continue // No trigger of this rule listens to this event.
            val binding = if (rule.triggerMatch.mode == MatchMode.ALL) {
                if (!allTriggersSatisfied(ruleBindings, rule.triggerMatch.windowSeconds, now)) continue
                eventBindings.firstOrNull { candidate -> matched.any { it.key == candidate.key } }
                    ?: eventBindings.first()
            } else {
                matched.firstOrNull { it.rule.id == rule.id } ?: continue
            }
            val debounceKey = if (rule.triggerMatch.mode == MatchMode.ALL) "${rule.id}#all" else binding.key
            if (!allowsDebounce(debounceKey, now)) {
                trace.record(
                    TraceEvent(
                        traceId = traceId,
                        atEpochMs = now,
                        kind = TraceKind.MATCH,
                        status = TraceStatus.DEBOUNCED,
                        level = TraceLevel.DEBUG,
                        ruleId = rule.id,
                        cmdId = rule.cmdId,
                        ruleName = rule.name,
                        componentTypeId = binding.component.typeId,
                        message = "触发被防抖",
                        detail = debounceKey,
                    ),
                )
                debounced += ruleBindings.size
                continue
            }
            val triggerVariables = variablesFor(binding, event, now, offset)
            // Conditions run outside the rule executor, so the host feeds persisted locals/globals here;
            // event outputs keep precedence over a same-named saved value.
            val persisted = runCatching { environment.persistedVariables(rule.id) }.getOrDefault(emptyMap())
            val conditionVariables = persisted + triggerVariables.values
            val conditions = TriggerConditions.evaluate(
                rule.conditions, environment, now, offset, conditionVariables,
            )
            for (evaluation in conditions.evaluations) {
                trace.record(
                    TraceEvent(
                        traceId = traceId,
                        atEpochMs = now,
                        kind = TraceKind.CONDITION,
                        status = if (evaluation.passed) TraceStatus.SUCCESS else TraceStatus.FAILURE,
                        level = if (evaluation.passed) TraceLevel.DEBUG else TraceLevel.INFO,
                        ruleId = rule.id,
                        cmdId = rule.cmdId,
                        ruleName = rule.name,
                        componentTypeId = evaluation.component.typeId,
                        message = evaluation.component.name,
                        detail = evaluation.detail,
                        params = TraceRedaction.params(
                            BuiltInComponents.registry().find(evaluation.component.typeId),
                            evaluation.component.params,
                        ),
                    ),
                )
            }
            if (!conditions.passed) {
                val detail = conditions.detail
                val previous = lastSkipLog[rule.id]
                if (previous == null || now - previous >= skipLogIntervalMs) {
                    lastSkipLog[rule.id] = now
                    val skip = TriggerSkip(rule, reasonFor(binding), detail)
                    skips += skip
                    skipRecorder.record(rule, skip.reason, detail, now)
                }
                continue
            }
            val reason = reasonFor(binding)
            val variables = triggerVariables.values
            val readOnlyNames = triggerVariables.readOnlyNames
            val active = executor.activeCount(rule)
            when (rule.runMode) {
                com.flowglass.engine.RunMode.DROP -> if (active > 0) {
                    dropped++
                    trace.record(
                        TraceEvent(
                            traceId = traceId,
                            atEpochMs = now,
                            kind = TraceKind.QUEUE,
                            status = TraceStatus.DROPPED,
                            level = TraceLevel.WARN,
                            ruleId = rule.id,
                            cmdId = rule.cmdId,
                            ruleName = rule.name,
                            message = "已有运行，按丢弃策略跳过",
                        ),
                    )
                    continue
                }
                com.flowglass.engine.RunMode.QUEUE -> if (active > 0) {
                    val queue = queues.getOrPut(rule.id) { ArrayDeque() }
                    if (queue.size >= MAX_QUEUED_RUNS) {
                        dropped++
                        trace.record(
                            TraceEvent(
                                traceId = traceId,
                                atEpochMs = now,
                                kind = TraceKind.QUEUE,
                                status = TraceStatus.DROPPED,
                                level = TraceLevel.WARN,
                                ruleId = rule.id,
                                cmdId = rule.cmdId,
                                ruleName = rule.name,
                                componentTypeId = binding.component.typeId,
                                message = "队列已满，丢弃触发",
                            ),
                        )
                    } else {
                        queue.addLast(TriggerExecution(rule, binding, reason, variables, readOnlyNames, traceId))
                        queued++
                        trace.record(
                            TraceEvent(
                                traceId = traceId,
                                atEpochMs = now,
                                kind = TraceKind.QUEUE,
                                status = TraceStatus.QUEUED,
                                ruleId = rule.id,
                                cmdId = rule.cmdId,
                                ruleName = rule.name,
                                componentTypeId = binding.component.typeId,
                                message = "已排队",
                            ),
                        )
                    }
                    continue
                }
                com.flowglass.engine.RunMode.RESTART -> if (active > 0) executor.cancel(rule)
                com.flowglass.engine.RunMode.PARALLEL -> if (active >= MAX_PARALLEL_RUNS) {
                    dropped++
                    trace.record(
                        TraceEvent(
                            traceId = traceId,
                            atEpochMs = now,
                            kind = TraceKind.QUEUE,
                            status = TraceStatus.DROPPED,
                            level = TraceLevel.WARN,
                            ruleId = rule.id,
                            cmdId = rule.cmdId,
                            ruleName = rule.name,
                            message = "并行运行数已满",
                        ),
                    )
                    continue
                }
            }
            lastFired[debounceKey] = now
            trace.record(
                TraceEvent(
                    traceId = traceId,
                    atEpochMs = now,
                    kind = TraceKind.MATCH,
                    status = TraceStatus.SUCCESS,
                    ruleId = rule.id,
                    cmdId = rule.cmdId,
                    ruleName = rule.name,
                    componentTypeId = binding.component.typeId,
                    message = "规则匹配",
                    detail = reasonFor(binding),
                ),
            )
            if (rule.triggerMatch.mode == MatchMode.ALL) {
                // A conjunction is consumed by one execution; require fresh events next time.
                ruleBindings.forEach { lastEvents.remove(it.key) }
            }
            if (executor.executeTraced(rule, binding, reason, variables, readOnlyNames, traceId)) {
                executions += TriggerExecution(rule, binding, reason, variables, readOnlyNames, traceId)
            } else {
                trace.record(
                    TraceEvent(
                        traceId = traceId,
                        atEpochMs = now,
                        kind = TraceKind.RUN,
                        status = TraceStatus.DROPPED,
                        level = TraceLevel.WARN,
                        ruleId = rule.id,
                        cmdId = rule.cmdId,
                        ruleName = rule.name,
                        componentTypeId = binding.component.typeId,
                        message = "执行器拒绝启动",
                    ),
                )
                dropped++
            }
        }
        return DispatchOutcome(executions, skips, debounced, dropped, queued)
    }

    /**
     * Called by the host when a run completes. A queued trigger starts immediately if the rule is
     * idle; the queue is bounded so a burst cannot grow without limit.
     */
    fun onRunFinished(rule: TriggerRule) {
        val queue = queues[rule.id] ?: return
        while (queue.isNotEmpty() && executor.activeCount(rule) == 0) {
            val next = queue.removeFirst()
            if (executor.executeTraced(
                    next.rule,
                    next.binding,
                    next.reason,
                    next.variables,
                    next.readOnlyNames,
                    next.traceId,
                )
            ) return
        }
        if (queue.isEmpty()) queues.remove(rule.id)
    }

    private fun allTriggersSatisfied(
        bindings: List<TriggerBinding>,
        windowSeconds: Int,
        now: Long,
    ): Boolean = bindings.all { binding ->
        if (TriggerMatcher.isStateful(binding)) {
            TriggerMatcher.matchesState(binding, environment)
        } else {
            val at = lastEvents[binding.key] ?: return@all false
            now - at <= windowSeconds.coerceAtLeast(0) * 1_000L
        }
    }

    /** Forget debounce/state history when the host re-registers after an explicit stop. */
    fun reset() {
        lastFired.clear()
        lastSkipLog.clear()
        states.clear()
        lastEvents.clear()
        queues.clear()
    }

    /** Drops state for rules that are no longer listening, so a re-enable starts from a baseline. */
    fun prune(rules: List<TriggerRule>) {
        val keys = rules.flatMap { TriggerPlan.bindings(it) }.map { it.key }.toSet()
        states.removeMissing(keys)
        lastFired.keys.retainAll(keys + rules.map { "${it.id}#all" })
        lastSkipLog.keys.retainAll(rules.map { it.id }.toSet())
        lastEvents.keys.retainAll(keys)
        val ruleIds = rules.map { it.id }.toSet()
        queues.keys.retainAll(ruleIds)
    }

    private fun allowsDebounce(key: String, now: Long): Boolean {
        val previous = lastFired[key] ?: return true
        return now - previous >= debounceMs
    }

    private fun reasonFor(binding: TriggerBinding): String = "触发：${binding.component.name}"

    private data class TriggerVariables(
        val values: Map<String, Any?>,
        val readOnlyNames: Set<String>,
    )

    private fun variablesFor(
        binding: TriggerBinding,
        event: TriggerEvent,
        now: Long,
        offset: Int,
    ): TriggerVariables {
        val localTime = TriggerSchedule.localTime(event.atEpochMs, offset)
        val variables = linkedMapOf<String, Any?>("event.time" to localTime)
        event.data["battery"]?.toIntOrNull()?.let { variables["event.battery"] = it }
        for ((key, value) in event.data) {
            if (key == "zone_offset_minutes" || key == "battery") continue
            variables["event.$key"] = value
        }
        val meta = BuiltInComponents.registry().find(binding.component.typeId)
        val readOnlyNames = linkedSetOf<String>()
        if (meta != null) {
            val values = triggerOutputValues(binding, event, localTime)
            for (output in OutputCatalog.effectiveBindings(binding.component.outputs, meta)) {
                if (!output.enabled) continue
                val spec = OutputCatalog.spec(meta, output.key) ?: continue
                val converted = OutputValues.convert(spec, values[output.key]) ?: continue
                variables[output.name] = converted
                readOnlyNames += output.name
            }
        }
        // User-named extraction fields are dynamic outputs, not part of the fixed schema.
        event.methodHookVariables.forEach { (key, value) ->
            variables[key] = value
            readOnlyNames += key
        }
        return TriggerVariables(variables, readOnlyNames)
    }

    private fun triggerOutputValues(
        binding: TriggerBinding,
        event: TriggerEvent,
        localTime: String,
    ): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        BuiltInComponents.registry().find(binding.component.typeId)?.let { meta ->
            OutputCatalog.primaryKey(meta)?.let { values[it] = localTime }
        }
        val data = event.data
        fun putText(key: String, value: String?) {
            // Only a missing field is "unset"; a present empty string is a real value for set/empty.
            if (value != null) values[key] = value
        }
        fun putApp() {
            putText("trigger.app.package", data["app"])
            data["app.user"]?.toIntOrNull()?.let { values["trigger.app.user"] = it }
        }
        when (binding.component.typeId) {
            TriggerTypes.BATTERY_LEVEL, TriggerTypes.BATTERY_LOW_LEGACY ->
                data["battery"]?.toIntOrNull()?.let { values["trigger.battery.level"] = it }
            TriggerTypes.WIFI -> {
                putText("trigger.wifi.ssid", data["ssid"])
                putText("trigger.wifi.state", data["state"])
            }
            TriggerTypes.BLUETOOTH -> {
                putText("trigger.bluetooth.name", data["name"])
                putText("trigger.bluetooth.state", data["state"])
            }
            TriggerTypes.NOTIFICATION, TriggerTypes.NOTIFICATION_REMOVED -> {
                putText("trigger.notification.app", data["notification.app"])
                data["notification.user"]?.toIntOrNull()?.let { values["trigger.notification.user"] = it }
                putText("trigger.notification.title", data["notification.title"])
                putText("trigger.notification.text", data["notification.text"])
                putText("trigger.notification.subtext", data["notification.subtext"])
                putText("trigger.notification.category", data["notification.category"])
                putText("trigger.notification.channel", data["notification.channel"])
                putText("trigger.notification.ongoing", data["notification.ongoing"])
            }
            TriggerTypes.APP_FOREGROUND -> putApp()
            TriggerTypes.APP_PROCESS, TriggerTypes.FOREGROUND_SERVICE -> putApp()
            TriggerTypes.APP_CRASH, TriggerTypes.MEDIA -> {
                putApp()
                val eventName = when (event.kind) {
                    TriggerKinds.APP_CRASH -> "crash"
                    TriggerKinds.APP_ANR -> "anr"
                    TriggerKinds.MEDIA_PLAYING -> "playing"
                    TriggerKinds.MEDIA_PAUSED -> "paused"
                    TriggerKinds.MEDIA_STOPPED -> "stopped"
                    else -> data["event"]
                }
                putText("trigger.app.event", eventName)
            }
            TriggerTypes.KEYGUARD -> putText("trigger.keyguard.state", data["event"])
            TriggerTypes.HEADSET -> {
                putText("trigger.headset.name", data["name"])
                putText("trigger.headset.kind", data["kind"])
                putText("trigger.headset.state", data["state"])
            }
            TriggerTypes.LOCATION_ENTER, TriggerTypes.LOCATION_EXIT -> {
                putText("trigger.location.lat", data["lat"])
                putText("trigger.location.lng", data["lng"])
            }
            TriggerTypes.APP_METHOD_CALL -> {
                putText("trigger.method.app", event.methodHookVariables[MethodHookVariables.APP] ?: data["app"])
                event.methodHookVariables[MethodHookVariables.USER]?.toIntOrNull()
                    ?.let { values["trigger.method.user"] = it }
                putText("trigger.method.process", event.methodHookVariables[MethodHookVariables.PROCESS])
                putText("trigger.method.method", event.methodHookVariables[MethodHookVariables.METHOD])
                putText("trigger.method.signature", event.methodHookVariables[MethodHookVariables.SIGNATURE])
                putText("trigger.method.hookId", data["hook.id"])
            }
            TriggerTypes.SYSTEM_TIME_CHANGED ->
                putText("trigger.system_time.kind", data["event"])
            TriggerTypes.NEXT_ALARM_CHANGED -> {
                putText("trigger.alarm.time", data["alarm.time"])
                putText("trigger.alarm.package", data["alarm.package"])
            }
            TriggerTypes.STORAGE_MEDIA -> {
                putText("trigger.storage.path", data["storage.path"])
                putText("trigger.storage.event", data["event"])
            }
            TriggerTypes.BATTERY_FULL ->
                data["battery"]?.toIntOrNull()?.let { values["trigger.battery.level"] = it }
            TriggerTypes.BATTERY_OVERHEAT ->
                data["battery.temperature"]?.toIntOrNull()
                    ?.let { values["trigger.battery.temperature"] = it }
            TriggerTypes.STORAGE_LOW ->
                data["storage.freePercent"]?.toIntOrNull()
                    ?.let { values["trigger.storage.freePercent"] = it }
            TriggerTypes.LOCALE_CHANGED -> putText("trigger.locale.tag", data["locale.tag"])
            TriggerTypes.PACKAGE_CHANGE -> {
                putText("trigger.package.name", data["package.name"])
                putText("trigger.package.label", data["package.label"])
                putText("trigger.package.event", data["event"])
            }
            TriggerTypes.SETTING_CHANGED -> {
                putText("trigger.setting.namespace", data["setting.namespace"])
                putText("trigger.setting.key", data["setting.key"])
                putText("trigger.setting.value", data["setting.value"])
            }
            TriggerTypes.TICK ->
                data["tick.interval"]?.toIntOrNull()?.let { values["trigger.tick.interval"] = it }
            TriggerTypes.ACCESSIBILITY_CHANGED -> {
                data["accessibility.count"]?.toIntOrNull()
                    ?.let { values["trigger.accessibility.count"] = it }
                putText("trigger.accessibility.services", data["accessibility.services"])
            }
            TriggerTypes.INTENT_RECEIVED -> {
                putText("trigger.intent.action", data["intent.action"])
                putText("trigger.intent.data", data["intent.data"])
                putText("trigger.intent.extras", data["intent.extras"])
            }
            TriggerTypes.HTTP_REQUEST -> {
                putText("trigger.http.method", data["http.method"])
                putText("trigger.http.path", data["http.path"])
                putText("trigger.http.query", data["http.query"])
                putText("trigger.http.body", data["http.body"])
                putText("trigger.http.remote", data["http.remote"])
            }
            TriggerTypes.SHARE_RECEIVED -> {
                putText("trigger.share.text", data["share.text"])
                putText("trigger.share.uri", data["share.uri"])
                putText("trigger.share.mime", data["share.mime"])
            }
            TriggerTypes.CALL_STATE -> putText("trigger.call.state", data["call.state"])
            TriggerTypes.SMS_RECEIVED -> {
                putText("trigger.sms.sender", data["sms.sender"])
                putText("trigger.sms.body", data["sms.body"])
            }
            TriggerTypes.MEDIA_TRACK -> {
                putText("trigger.media.title", data["media.title"])
                putText("trigger.media.artist", data["media.artist"])
                putText("trigger.media.album", data["media.album"])
                putText("trigger.media.package", data["media.package"])
            }
        }
        return values
    }
}
