package com.flowglass.demo.ui

import com.flowglass.demo.system.exec.StepResult
import com.flowglass.demo.system.exec.StepStatus as UiStepStatus
import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentPresentation
import com.flowglass.engine.LegacyCommandMigration
import com.flowglass.engine.PersistedLog
import com.flowglass.engine.PersistedLogStep
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleComponent
import com.flowglass.engine.RuleDocument
import com.flowglass.engine.RulePresentation
import com.flowglass.engine.RuleUiMetadata
import kotlinx.serialization.json.JsonPrimitive

internal fun PersistedAppState.toRuleDocument(): RuleDocument = RuleDocument(
    rules = (autoCmds + oneTaps).map { command ->
        val oldRuleId = engineUi.presentation.entries.firstOrNull { it.value.legacyId == command.id }?.key
        val oldRule = engineRules.firstOrNull { it.id == oldRuleId }
        val oldPresentation = oldRuleId?.let(engineUi.presentation::get)
        fun prior(
            items: List<CommandItem>,
            kind: ComponentKind,
            old: List<RuleComponent>,
            sidecar: List<ComponentPresentation>,
        ) =
            items.map { item ->
                val oldIndex = sidecar.indexOfFirst { it.id == item.id }
                item.toRuleComponent(
                    kind,
                    when (oldIndex) {
                        in old.indices -> old[oldIndex]
                        else -> null
                    },
                    when (oldIndex) {
                        in sidecar.indices -> sidecar[oldIndex]
                        else -> null
                    },
                )
            }
        Rule(
            id = oldRule?.id ?: uuidFor(command.id),
            name = command.title,
            description = command.desc,
            type = if (command.auto) "auto" else "manual",
            enabled = command.enabled,
            runMode = oldRule?.runMode ?: "drop",
            triggerMatch = oldRule?.triggerMatch ?: com.flowglass.engine.MatchSpec(mode = "any"),
            conditionMatch = oldRule?.conditionMatch ?: com.flowglass.engine.MatchSpec(mode = "all"),
            locals = oldRule?.locals.orEmpty(),
            triggers = prior(command.triggers, ComponentKind.TRIGGER, oldRule?.triggers.orEmpty(), oldPresentation?.triggers.orEmpty())
                .mapIndexed { index, component -> component.copy(type = command.triggers[index].typeId ?: component.type) },
            conditions = prior(command.conditions, ComponentKind.CONDITION, oldRule?.conditions.orEmpty(), oldPresentation?.conditions.orEmpty())
                .mapIndexed { index, component -> component.copy(type = command.conditions[index].typeId ?: component.type) },
            actions = prior(command.actions, ComponentKind.ACTION, oldRule?.actions.orEmpty(), oldPresentation?.actions.orEmpty())
                .mapIndexed { index, component -> component.copy(type = command.actions[index].typeId ?: component.type) },
        )
    },
    ui = engineUi.copy(
        running = running,
        mode = mode,
        logs = logs.map { log ->
            PersistedLog(
                id = log.id,
                title = log.title,
                reason = log.reason,
                time = log.time,
                ok = log.ok,
                icon = log.ic.name,
                detail = log.detail,
                steps = log.steps.map { step ->
                    PersistedLogStep(step.itemId, step.name, step.status.name, step.detail, step.durationMs)
                },
            )
        },
        presentation = (engineUi.presentation.filterKeys { id -> engineRules.any { it.id == id } }) +
            (autoCmds + oneTaps).associate { command ->
                val ruleId = engineRules.firstOrNull { rule ->
                    engineUi.presentation[rule.id]?.legacyId == command.id
                }?.id ?: uuidFor(command.id)
                ruleId to RulePresentation(
                    category = command.category,
                    icon = command.ic.name,
                    pinned = command.pinned,
                    lastRun = command.lastRun,
                    descriptionEdited = command.descriptionEdited,
                    legacyId = command.id,
                    triggers = command.triggers.map { it.toPresentation() },
                    conditions = command.conditions.map { it.toPresentation() },
                    actions = command.actions.map { it.toPresentation() },
                )
            },
    ),
)

internal fun RuleDocument.toPersistedAppState(): PersistedAppState {
    require(formatVersion == com.flowglass.engine.CURRENT_FORMAT_VERSION)
    val metadata = ui
    val usedIds = (metadata.logs.flatMap { log -> listOf(log.id) + log.steps.map { it.itemId } } +
        metadata.presentation.values.flatMap { sidecar ->
            listOfNotNull(sidecar.legacyId) + (sidecar.triggers + sidecar.conditions + sidecar.actions).map { it.id }
        }).toMutableSet()
    var nextId = maxOf(1000L, (usedIds.maxOrNull() ?: 999L) + 1L)
    fun allocateId(): Long {
        while (nextId <= 0L || nextId == Long.MAX_VALUE || nextId in usedIds) nextId++
        check(nextId < Long.MAX_VALUE) { "兼容标识已用尽" }
        return nextId++.also(usedIds::add)
    }
    val normalizedPresentation = metadata.presentation.toMutableMap()
    val rules = this.rules.map { rule ->
        val sidecar = metadata.presentation[rule.id] ?: RulePresentation()
        val commandId = sidecar.legacyId ?: allocateId()
        val auto = rule.type == "auto"
        require(auto || rule.type == "manual") { "规则类型无效" }
        fun items(components: List<RuleComponent>, old: List<ComponentPresentation>) = components.mapIndexed { index, item ->
            val prior = old.getOrNull(index)
            val mapped = item.toCommandItem(prior, ::allocateId)
            if (prior == null) usedIds.add(mapped.id)
            mapped
        }
        val triggers = items(rule.triggers, sidecar.triggers)
        val conditions = items(rule.conditions, sidecar.conditions)
        val actions = items(rule.actions, sidecar.actions)
        val command = Cmd(
            id = commandId,
            auto = auto,
            ic = iconOrDefault(sidecar.icon),
            title = rule.name,
            desc = rule.description,
            triggers = triggers,
            conditions = conditions,
            actions = actions,
            category = sidecar.category,
            enabled = rule.enabled,
            pinned = sidecar.pinned,
            lastRun = sidecar.lastRun,
            descriptionEdited = sidecar.descriptionEdited,
        )
        normalizedPresentation[rule.id] = sidecar.copy(
            legacyId = commandId,
            triggers = triggers.map { it.toPresentation() },
            conditions = conditions.map { it.toPresentation() },
            actions = actions.map { it.toPresentation() },
        )
        command
    }
    require(rules.map { it.id }.distinct().size == rules.size) { "规则兼容标识重复" }
    return PersistedAppState(
        autoCmds = rules.filter { it.auto },
        oneTaps = rules.filterNot { it.auto },
        logs = this.ui.logs.map { log ->
            RunLog(log.id, log.title, log.reason, log.time, log.ok, iconOrDefault(log.icon), log.detail,
                log.steps.map { step -> StepResult(step.itemId, step.name, UiStepStatus.valueOf(step.status), step.detail, step.durationMs) })
        },
        running = this.ui.running,
        mode = this.ui.mode,
        engineRules = this.rules,
        engineUi = ui.copy(presentation = normalizedPresentation),
    )
}

private fun CommandItem.toRuleComponent(
    kind: ComponentKind,
    old: RuleComponent?,
    oldPresentation: ComponentPresentation?,
): RuleComponent {
    val typeId = this.typeId ?: typeIdFor(name, kind)
    val params = when (typeId) {
        "action.post_notification" -> {
            val split = parameter.indexOf('|')
            mapOf(
                "content" to JsonPrimitive(if (split < 0) parameter else parameter.substring(split + 1)),
                "title" to JsonPrimitive(if (split < 0) "" else parameter.substring(0, split)),
            )
        }
        "legacy.unknown" -> mapOf("parameter" to JsonPrimitive(parameter))
        else -> mapOf(parameterKey(typeId) to JsonPrimitive(parameter))
    }
    return RuleComponent(
        type = typeId,
        params = if (old?.type == typeId) old.params + params else params,
        onError = old?.takeIf { it.type == typeId }?.onError ?: "stop",
    )
}

private fun CommandItem.toPresentation() = ComponentPresentation(id, name, parameter, ic.name)

private fun RuleComponent.toCommandItem(old: ComponentPresentation?, allocateId: () -> Long): CommandItem {
    val param = if (type == "action.post_notification") {
        val title = (params["title"] as? JsonPrimitive)?.content.orEmpty()
        val body = (params["content"] as? JsonPrimitive)?.content.orEmpty()
        "$title|$body"
    } else params.values.firstOrNull()?.let { if (it is JsonPrimitive) it.content else it.toString() }.orEmpty()
    val name = old?.name?.ifBlank { knownName(type) ?: "未知组件" } ?: knownName(type) ?: "未知组件"
    val displayParameter = when {
        type == "legacy.unknown" -> (params["parameter"] as? JsonPrimitive)?.content
            ?: old?.parameter
            ?: (params["raw"] as? JsonPrimitive)?.content.orEmpty()
        type == "action.post_notification" -> param
        else -> param.ifEmpty { old?.parameter.orEmpty() }
    }
    return CommandItem(old?.id ?: allocateId(), name, displayParameter, iconOrDefault(old?.icon ?: "Bolt"), type)
}

private fun typeIdFor(name: String, kind: ComponentKind): String = when (kind) {
    ComponentKind.ACTION -> actionTypes[name] ?: "legacy.unknown"
    ComponentKind.TRIGGER -> triggerTypes[name] ?: "legacy.unknown"
    ComponentKind.CONDITION -> conditionTypes[name] ?: "legacy.unknown"
}

private fun parameterKey(type: String): String = when (type) {
    "action.wifi_switch", "action.bluetooth_switch", "action.dnd_switch" -> "mode"
    "action.set_brightness", "action.set_volume" -> "value"
    "action.launch_app" -> "package"
    "action.post_notification" -> "content"
    "action.copy_clipboard" -> "text"
    "action.delay" -> "duration"
    "action.root_shell" -> "command"
    "action.http_request" -> "request"
    "trigger.time" -> "time"
    "trigger.location_enter", "trigger.location_exit" -> "location"
    "trigger.notification_received" -> "text"
    "trigger.battery_low" -> "threshold"
    "trigger.charging" -> "state"
    "trigger.bluetooth_connected" -> "name"
    "trigger.wifi_changed" -> "ssid"
    "trigger.application_started" -> "package"
    "condition.time_range", "condition.battery_range" -> "range"
    "condition.weekday" -> "days"
    "condition.network_type" -> "network"
    "condition.screen_state", "condition.dnd_state" -> "state"
    "legacy.unknown" -> "raw"
    else -> "value"
}

private fun knownName(type: String): String? = (actionTypes + triggerTypes + conditionTypes).entries.firstOrNull { it.value == type }?.key

private fun iconOrDefault(value: String): Ic = runCatching { Ic.valueOf(value) }.getOrDefault(Ic.Bolt)

private fun uuidFor(id: Long): String = LegacyCommandMigration.uuidForLegacyId(id)

private val actionTypes = mapOf(
    "开关 WiFi" to "action.wifi_switch",
    "开关蓝牙" to "action.bluetooth_switch",
    "开关勿扰" to "action.dnd_switch",
    "调节亮度" to "action.set_brightness",
    "调节音量" to "action.set_volume",
    "启动应用" to "action.launch_app",
    "发送通知" to "action.post_notification",
    "复制到剪贴板" to "action.copy_clipboard",
    "延时等待" to "action.delay",
    "执行 Shell(Root)" to "action.root_shell",
    "HTTP 请求" to "action.http_request",
)

private val triggerTypes = mapOf(
    "时间" to "trigger.time",
    "进入地点" to "trigger.location_enter",
    "离开地点" to "trigger.location_exit",
    "收到通知" to "trigger.notification_received",
    "电量低于" to "trigger.battery_low",
    "充电状态" to "trigger.charging",
    "蓝牙连接" to "trigger.bluetooth_connected",
    "WiFi 连接/断开" to "trigger.wifi_changed",
    "应用启动" to "trigger.application_started",
    "开机完成" to "trigger.boot_completed",
)

private val conditionTypes = mapOf(
    "时间段" to "condition.time_range",
    "星期" to "condition.weekday",
    "电量范围" to "condition.battery_range",
    "网络类型" to "condition.network_type",
    "屏幕状态" to "condition.screen_state",
    "勿扰状态" to "condition.dnd_state",
)
