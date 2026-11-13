package com.flowglass.demo.ui

import com.flowglass.demo.system.exec.StepResult
import com.flowglass.demo.system.exec.StepStatus as UiStepStatus
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ParameterCodec
import com.flowglass.engine.RuleType
import com.flowglass.engine.RunMode
import com.flowglass.engine.MatchMode
import com.flowglass.engine.OnError
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

internal fun PersistedAppState.toRuleDocument(includeTriggersAndConditions: Boolean = true): RuleDocument = RuleDocument(
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
                )
            }
        Rule(
            id = oldRule?.id ?: uuidFor(command.id),
            name = command.title,
            description = command.desc,
            type = if (command.auto) RuleType.AUTO else RuleType.MANUAL,
            enabled = command.enabled,
            runMode = oldRule?.runMode ?: RunMode.DROP,
            triggerMatch = oldRule?.triggerMatch ?: com.flowglass.engine.MatchSpec(mode = MatchMode.ANY),
            conditionMatch = oldRule?.conditionMatch ?: com.flowglass.engine.MatchSpec(mode = MatchMode.ALL),
            locals = oldRule?.locals.orEmpty(),
            triggers = if (includeTriggersAndConditions) {
                prior(command.triggers, ComponentKind.TRIGGER, oldRule?.triggers.orEmpty(), oldPresentation?.triggers.orEmpty())
            } else emptyList(),
            conditions = if (includeTriggersAndConditions) {
                prior(command.conditions, ComponentKind.CONDITION, oldRule?.conditions.orEmpty(), oldPresentation?.conditions.orEmpty())
            } else emptyList(),
            actions = prior(command.actions, ComponentKind.ACTION, oldRule?.actions.orEmpty(), oldPresentation?.actions.orEmpty()),
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
    require(metadata.mode in 0..2) { "运行模式无效" }
    require(rules.map { it.id }.distinct().size == rules.size) { "规则标识重复" }
    require(rules.all { it.type != RuleType.MANUAL || (it.triggers.isEmpty() && it.conditions.isEmpty()) }) {
        "一键指令不能包含触发器或条件"
    }
    require(metadata.presentation.values.all { it.category in 0..6 }) { "指令分类无效" }
    val commandIds = metadata.presentation.values.mapNotNull { it.legacyId }
    require(commandIds.all { it > 0L && it < Long.MAX_VALUE } && commandIds.distinct().size == commandIds.size) {
        "指令 id 必须为正数、小于 Long.MAX_VALUE 且不重复"
    }
    val itemIds = metadata.presentation.values.flatMap { it.triggers + it.conditions + it.actions }.map { it.id }
    val commandIdSet = commandIds.toSet()
    require(itemIds.all { it > 0L && it < Long.MAX_VALUE && it !in commandIdSet } && itemIds.distinct().size == itemIds.size) {
        "条目 id 必须为正数、小于 Long.MAX_VALUE、不重复且不与指令 id 冲突"
    }
    val logIds = metadata.logs.map { it.id }
    val commandAndItemIds = (commandIds + itemIds).toSet()
    // Legacy nonpositive log IDs have already been replaced by the migration.
    // Canonical v2 declarations stay strict; only references may repeat.
    require(logIds.all { it > 0L && it < Long.MAX_VALUE && it !in commandAndItemIds } && logIds.distinct().size == logIds.size) {
        "执行记录 id 必须为正数、小于 Long.MAX_VALUE、不重复且不与指令、条目 id 冲突"
    }
    val declaredIds = commandIds + itemIds + logIds
    val steps = metadata.logs.flatMap { it.steps }
    require(steps.all { it.itemId > 0L && it.itemId < Long.MAX_VALUE && it.durationMs >= 0L }) {
        "执行记录步骤标识或耗时无效"
    }
    val usedIds = (declaredIds + steps.map { it.itemId }).toMutableSet()
    var nextId = maxOf(1000L, (usedIds.maxOrNull() ?: 999L) + 1L)
    fun allocateId(): Long {
        // nextId starts above every declaration/reference; no collision scan is needed.
        // Check BEFORE incrementing so exhaustion cannot wrap into a negative-ID loop.
        check(nextId < Long.MAX_VALUE) { "兼容标识已用尽" }
        return nextId++.also(usedIds::add)
    }
    val normalizedPresentation = metadata.presentation.toMutableMap()
    val rules = this.rules.map { rule ->
        val sidecar = metadata.presentation[rule.id] ?: RulePresentation()
        val commandId = sidecar.legacyId ?: allocateId()
        val auto = rule.type == RuleType.AUTO
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

private val componentRegistry = BuiltInComponents.registry()

private fun CommandItem.toRuleComponent(
    kind: ComponentKind,
    old: RuleComponent?,
): RuleComponent {
    val typeId = this.typeId ?: componentRegistry.byKind(kind).firstOrNull { it.name == name }?.typeId ?: "legacy.unknown"
    val previous = old?.takeIf { it.type == typeId }
    // Compare with a projection of authoritative params, never persisted display text.
    if (previous != null && parameter == previous.parameterText()) return previous
    val meta = componentRegistry.find(typeId)
    if (meta == null) {
        if (previous != null) return previous.copy(params = previous.params + mapOf(
            "parameter" to JsonPrimitive(parameter), "name" to JsonPrimitive(name),
            "raw" to JsonPrimitive("$name：$parameter"),
        ))
        return unknownComponent()
    }
    // A live edit is not a legacy migration: reject invalid text before touching disk.
    // The editor retains the draft while the last valid component and its extensions remain recoverable.
    val parsed = ParameterCodec.parseComponent(meta, parameter)
    val changed = if (previous != null && typeId == "action.post_notification") {
        val oldFields = notificationFields(previous.parameterText())
        val newFields = notificationFields(parameter)
        parsed.filterKeys { oldFields[it] != newFields[it] }
    } else parsed
    return RuleComponent(
        type = typeId,
        params = previous?.params.orEmpty() + changed,
        onError = previous?.onError ?: OnError.STOP,
    )
}

private fun CommandItem.unknownComponent() = RuleComponent(
    type = "legacy.unknown",
    params = mapOf(
        "name" to JsonPrimitive(name),
        "parameter" to JsonPrimitive(parameter),
        "icon" to JsonPrimitive(ic.name),
        "raw" to JsonPrimitive("$name：$parameter"),
    ),
)

private fun CommandItem.toPresentation() = ComponentPresentation(id, ic.name)

private fun notificationFields(parameter: String): Map<String, String> {
    val split = parameter.indexOf('|')
    return mapOf(
        "content" to if (split < 0) parameter else parameter.substring(split + 1),
        "title" to if (split < 0) "" else parameter.substring(0, split),
    )
}

private fun kotlinx.serialization.json.JsonElement.displayText(): String =
    if (this is JsonPrimitive) content else toString()

private fun RuleComponent.parameterText(): String {
    val meta = componentRegistry.find(type)
    return if (meta != null) ParameterCodec.formatComponent(meta, params)
    else params["parameter"]?.displayText() ?: params["raw"]?.displayText().orEmpty()
}

private fun RuleComponent.toCommandItem(old: ComponentPresentation?, allocateId: () -> Long): CommandItem {
    val name = componentRegistry.find(type)?.name ?: params["name"]?.displayText() ?: "未知组件"
    return CommandItem(old?.id ?: allocateId(), name, parameterText(),
        iconOrDefault(old?.icon ?: params["icon"]?.displayText() ?: "Bolt"), type)
}

private fun iconOrDefault(value: String): Ic = runCatching { Ic.valueOf(value) }.getOrDefault(Ic.Bolt)

private fun uuidFor(id: Long): String = LegacyCommandMigration.uuidForLegacyId(id)
