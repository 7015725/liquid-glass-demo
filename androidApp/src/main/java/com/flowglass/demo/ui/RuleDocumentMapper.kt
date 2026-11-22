package com.flowglass.demo.ui

import com.flowglass.demo.system.exec.StepResult
import com.flowglass.demo.system.exec.StepStatus as UiStepStatus
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ParameterCodec
import com.flowglass.engine.RuleType
import com.flowglass.engine.MatchMode
import com.flowglass.engine.OnError
import com.flowglass.engine.OutputCatalog
import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentPresentation
import com.flowglass.engine.LegacyCommandMigration
import com.flowglass.engine.PersistedLog
import com.flowglass.engine.PersistedLogStep
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleBranch
import com.flowglass.engine.RuleComponent
import com.flowglass.engine.RuleDocument
import com.flowglass.engine.normalizeElseIfBranches
import com.flowglass.engine.RulePresentation
import com.flowglass.engine.RuleUiMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal fun PersistedAppState.toRuleDocument(includeTriggersAndConditions: Boolean = true): RuleDocument = RuleDocument(
    rules = (autoCmds + oneTaps).map { command ->
        val oldRuleId = engineUi.presentation.entries.firstOrNull { it.value.legacyId == command.id }?.key
        val oldRule = engineRules.firstOrNull { it.id == oldRuleId }
        val oldPresentation = oldRuleId?.let(engineUi.presentation::get)
        // Ids survive reordering; the positional fallback only serves pre-v3 files.
        val oldById = oldRule?.let { rule ->
            (rule.triggers + rule.conditions + rule.actions).allComponents().associateBy { it.id }
        }.orEmpty()
        fun prior(
            items: List<CommandItem>,
            kind: ComponentKind,
            old: List<RuleComponent>,
            sidecar: List<ComponentPresentation>,
        ) = items.mapIndexed { index, item ->
            item.toRuleComponent(
                kind,
                oldById[item.id] ?: if (item.id <= 0L) old.getOrNull(index) else null,
                sidecar.getOrNull(index),
                oldById,
            )
        }
        Rule(
            id = oldRule?.id ?: uuidFor(command.id),
            name = command.title,
            description = command.desc,
            type = if (command.auto) RuleType.AUTO else RuleType.MANUAL,
            enabled = command.enabled,
            runMode = command.runMode,
            triggerMatch = command.triggerMatch,
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
                    PersistedLogStep(step.itemId, step.name, step.status.name, step.detail, step.durationMs,
                        step.layer?.name)
                },
                skipped = log.skipped,
                at = log.at,
                durationMs = log.durationMs,
            )
        },
        presentation = (engineUi.presentation.filterKeys { id -> engineRules.any { it.id == id } }) +
            (autoCmds + oneTaps).associate { command ->
                val ruleId = engineRules.firstOrNull { rule ->
                    engineUi.presentation[rule.id]?.legacyId == command.id
                }?.id ?: uuidFor(command.id)
                val previous = engineUi.presentation[ruleId]
                ruleId to RulePresentation(
                    category = command.category,
                    icon = command.ic.name,
                    pinned = command.pinned,
                    lastRun = command.lastRun,
                    descriptionEdited = command.descriptionEdited,
                    legacyId = command.id,
                    // v3: one id -> icon entry per node, no positional lists.
                    items = (command.triggers + command.conditions + command.actions).allItems()
                        .associate { it.id to ComponentPresentation(it.id, it.ic.name) },
                    collapsed = (command.triggers + command.conditions + command.actions).allItems()
                        .filter { it.collapsed }.map { it.id },
                    triggers = emptyList(),
                    conditions = emptyList(),
                    actions = emptyList(),
                )
            },
    ),
    globals = engineGlobals,
    globalMeta = engineGlobalMeta,
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
    val itemIds = metadata.presentation.values.flatMap { sidecar ->
        sidecar.items.keys + (sidecar.triggers + sidecar.conditions + sidecar.actions).map { it.id }
    }
    val commandIdSet = commandIds.toSet()
    require(itemIds.all { it > 0L && it < Long.MAX_VALUE && it !in commandIdSet } && itemIds.distinct().size == itemIds.size) {
        "条目 id 必须为正数、小于 Long.MAX_VALUE、不重复且不与指令 id 冲突"
    }
    val logIds = metadata.logs.map { it.id }
    val commandAndItemIds = (commandIds + itemIds).toSet()
    // Legacy nonpositive log IDs have already been replaced by the migration.
    // Canonical v3 declarations stay strict; only references may repeat.
    require(logIds.all { it > 0L && it < Long.MAX_VALUE && it !in commandAndItemIds } && logIds.distinct().size == logIds.size) {
        "执行记录 id 必须为正数、小于 Long.MAX_VALUE、不重复且不与指令、条目 id 冲突"
    }
    val declaredIds = commandIds + itemIds + logIds
    val steps = metadata.logs.flatMap { it.steps }
    require(steps.all { it.itemId > 0L && it.itemId < Long.MAX_VALUE && it.durationMs >= 0L }) {
        "执行记录步骤标识或耗时无效"
    }
    val treeIds = rules.flatMap { it.triggers.allComponents() + it.conditions.allComponents() + it.actions.allComponents() }
        .mapNotNull { it.id }
    val usedIds = (declaredIds + steps.map { it.itemId } + treeIds).toMutableSet()
    var nextId = maxOf(1000L, (usedIds.maxOrNull() ?: 999L) + 1L)
    fun allocateId(): Long {
        // nextId starts above every declaration/reference; no collision scan is needed.
        // Check BEFORE incrementing so exhaustion cannot wrap into a negative-ID loop.
        check(nextId < Long.MAX_VALUE) { "兼容标识已用尽" }
        return nextId++.also(usedIds::add)
    }
    // 组件树才是 ID 权威：历史/导入文档里同一 ID 可能出现在多个容器，sidecar 会被折叠成
    // 一个键，之后按 ID 查找会同时改到多条。首个出现保留原 ID，后续重号分配新 ID。
    val seenComponentIds = (commandIds + logIds).toMutableSet()
    fun claimId(candidate: Long?): Long? =
        candidate?.takeIf { it > 0L && seenComponentIds.add(it) }
    val normalizedPresentation = metadata.presentation.toMutableMap()
    val rules = this.rules.map { rule ->
        val sidecar = metadata.presentation[rule.id] ?: RulePresentation()
        val commandId = sidecar.legacyId ?: allocateId()
        val auto = rule.type == RuleType.AUTO
        fun items(
            components: List<RuleComponent>,
            old: List<ComponentPresentation>,
        ) = components.mapIndexed { index, component ->
            component.toCommandItem(old.getOrNull(index), sidecar, ::allocateId, ::claimId)
        }
        val triggers = items(rule.triggers, sidecar.triggers)
        val conditions = items(rule.conditions, sidecar.conditions)
        val actions = items(rule.actions.map { it.normalizeElseIfBranches() }, sidecar.actions)
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
            triggerMatch = rule.triggerMatch,
            runMode = rule.runMode,
        )
        val all = (triggers + conditions + actions).allItems()
        normalizedPresentation[rule.id] = sidecar.copy(
            legacyId = commandId,
            items = all.associate { it.id to ComponentPresentation(it.id, it.ic.name) },
            collapsed = all.filter { it.collapsed }.map { it.id },
            triggers = emptyList(),
            conditions = emptyList(),
            actions = emptyList(),
        )
        command
    }
    require(rules.map { it.id }.distinct().size == rules.size) { "规则兼容标识重复" }
    // Components carry the same UI ids as their CommandItem counterparts, so the next save can
    // pair old and new by id instead of position (and keep unknown extension params).
    val annotatedRules = this.rules.zip(rules).map { (rule, command) ->
        rule.copy(
            triggers = rule.triggers.mapIndexed { index, component ->
                command.triggers.getOrNull(index)?.let(component::withIds) ?: component
            },
            conditions = rule.conditions.mapIndexed { index, component ->
                command.conditions.getOrNull(index)?.let(component::withIds) ?: component
            },
            actions = rule.actions.mapIndexed { index, component ->
                command.actions.getOrNull(index)?.let(component::withIds) ?: component
            },
        )
    }
    return PersistedAppState(
        autoCmds = rules.filter { it.auto },
        oneTaps = rules.filterNot { it.auto },
        logs = this.ui.logs.map { log ->
            RunLog(log.id, log.title, log.reason, log.time, log.ok, iconOrDefault(log.icon), log.detail,
                log.steps.map { step ->
                    StepResult(step.itemId, step.name, UiStepStatus.valueOf(step.status), step.detail,
                        step.durationMs, step.layer?.let { name ->
                            runCatching { com.flowglass.engine.ExecutionLayer.valueOf(name) }.getOrNull()
                        })
                },
                log.skipped, at = log.at, durationMs = log.durationMs)
        },
        running = this.ui.running,
        mode = this.ui.mode,
        engineRules = annotatedRules,
        engineUi = ui.copy(presentation = normalizedPresentation),
        engineGlobals = globals,
        engineGlobalMeta = globalMeta,
    )
}

private val componentRegistry = BuiltInComponents.registry()

private fun CommandItem.toRuleComponent(
    kind: ComponentKind,
    old: RuleComponent?,
    oldPresentation: ComponentPresentation?,
    oldById: Map<Long?, RuleComponent>,
): RuleComponent {
    val typeId = this.typeId ?: componentRegistry.byKind(kind).firstOrNull { it.name == name }?.typeId ?: "legacy.unknown"
    val previous = old?.takeIf { it.type == typeId }
    // 先在全树按稳定 ID 找：步骤可以跨容器移动，只看目标旧父节点的孩子会找不到原节点，
    // 再按位置借用同类型兄弟，把别人的扩展参数当成自己的。位置回退只服务无 ID 的旧版条目。
    fun oldChild(list: List<RuleComponent>?, id: Long?, index: Int): RuleComponent? =
        list?.firstOrNull { it.id != null && it.id == id }
            ?: (if (id != null && id > 0L) oldById[id] else null)
            ?: if (id == null || id <= 0L) list?.getOrNull(index) else null
    val children = children.mapIndexed { index, child ->
        child.toRuleComponent(ComponentKind.ACTION, oldChild(previous?.children, child.id, index), null, oldById)
    }
    val branches = branches.mapIndexed { index, branch ->
        val oldBranch = previous?.branches?.getOrNull(index)
        RuleBranch(
            conditions = branch.conditions.mapIndexed { cIndex, condition ->
                condition.toRuleComponent(
                    ComponentKind.CONDITION,
                    oldChild(oldBranch?.conditions, condition.id, cIndex),
                    null,
                    oldById,
                )
            },
            children = branch.children.mapIndexed { cIndex, child ->
                child.toRuleComponent(
                    ComponentKind.ACTION,
                    oldChild(oldBranch?.children, child.id, cIndex),
                    null,
                    oldById,
                )
            },
        )
    }
    val conditions = conditions.mapIndexed { index, condition ->
        condition.toRuleComponent(
            ComponentKind.CONDITION,
            oldChild(previous?.conditions, condition.id, index),
            null,
            oldById,
        )
    }
    // Compare with a projection of authoritative params, never persisted display text.
    if (previous != null && parameter == previous.parameterText() && outputs == previous.outputs &&
        children.size == previous.children.size && branches.size == previous.branches.size &&
        conditions.size == previous.conditions.size) {
        return previous.copy(id = id, children = children, branches = branches, conditions = conditions)
    }
    val meta = componentRegistry.find(typeId)
    if (meta == null) {
        if (previous != null) return previous.copy(
            id = id,
            params = previous.params + mapOf(
                "parameter" to JsonPrimitive(parameter), "name" to JsonPrimitive(name),
                "raw" to JsonPrimitive("$name：$parameter"),
            ),
            children = children, branches = branches, conditions = conditions,
            outputs = outputs,
        )
        return unknownComponent().copy(id = id, children = children, branches = branches, conditions = conditions)
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
        id = id,
        type = typeId,
        params = previous?.params.orEmpty() + changed,
        onError = onError,
        children = children,
        branches = branches,
        conditions = conditions,
        outputs = if (outputs.isNotEmpty()) outputs else if (meta.kind == ComponentKind.CONDITION) emptyList() else OutputCatalog.defaultBindings(meta),
    )
}

private fun CommandItem.unknownComponent() = RuleComponent(
    onError = onError,
    type = "legacy.unknown",
    params = mapOf(
        "name" to JsonPrimitive(name),
        "parameter" to JsonPrimitive(parameter),
        "icon" to JsonPrimitive(ic.name),
        "raw" to JsonPrimitive("$name：$parameter"),
    ),
)

private fun notificationFields(parameter: String): Map<String, String> {
    val meta = componentRegistry.find("action.post_notification")
    val parsed = meta?.let { runCatching { ParameterCodec.parseComponent(it, parameter) }.getOrNull() }
    if (parsed != null) {
        return mapOf(
            "content" to (parsed["content"]?.displayText().orEmpty()),
            "title" to (parsed["title"]?.displayText().orEmpty()),
            "mode" to (parsed["mode"]?.displayText() ?: "system"),
            "toastDuration" to (parsed["toastDuration"]?.displayText() ?: "short"),
        )
    }
    val split = parameter.indexOf('|')
    return mapOf(
        "content" to if (split < 0) parameter else parameter.substring(split + 1),
        "title" to if (split < 0) "" else parameter.substring(0, split),
        "mode" to "system",
        "toastDuration" to "short",
    )
}

private fun kotlinx.serialization.json.JsonElement.displayText(): String =
    if (this is JsonPrimitive) content else toString()

private fun RuleComponent.parameterText(): String {
    val meta = componentRegistry.find(type)
    return if (meta != null) ParameterCodec.formatComponent(meta, params)
    else params["parameter"]?.displayText() ?: params["raw"]?.displayText().orEmpty()
}

private fun RuleComponent.toCommandItem(
    old: ComponentPresentation?,
    sidecar: RulePresentation,
    allocateId: () -> Long,
    claimId: (Long?) -> Long?,
): CommandItem {
    val id = claimId(this.id) ?: claimId(old?.id) ?: allocateId()
    val icon = sidecar.items[id]?.icon ?: old?.icon ?: params["icon"]?.displayText() ?: "Bolt"
    return CommandItem(
        id = id,
        name = componentRegistry.find(type)?.name ?: params["name"]?.displayText() ?: "未知组件",
        parameter = parameterText(),
        ic = iconOrDefault(icon),
        typeId = type,
        onError = onError,
        children = children.map { it.toCommandItem(null, sidecar, allocateId, claimId) },
        branches = branches.map { branch ->
            CommandBranch(
                children = branch.children.map { it.toCommandItem(null, sidecar, allocateId, claimId) },
                conditions = branch.conditions.map { it.toCommandItem(null, sidecar, allocateId, claimId) },
            )
        },
        conditions = conditions.map { it.toCommandItem(null, sidecar, allocateId, claimId) },
        collapsed = id in sidecar.collapsed,
        outputs = outputs,
    )
}

private fun RuleComponent.withIds(item: CommandItem): RuleComponent = copy(
    id = item.id,
    children = children.mapIndexed { index, child ->
        item.children.getOrNull(index)?.let(child::withIds) ?: child
    },
    conditions = conditions.mapIndexed { index, condition ->
        item.conditions.getOrNull(index)?.let(condition::withIds) ?: condition
    },
    branches = branches.mapIndexed { index, branch ->
        val mapped = item.branches.getOrNull(index)
        branch.copy(
            conditions = branch.conditions.mapIndexed { cIndex, condition ->
                mapped?.conditions?.getOrNull(cIndex)?.let(condition::withIds) ?: condition
            },
            children = branch.children.mapIndexed { cIndex, child ->
                mapped?.children?.getOrNull(cIndex)?.let(child::withIds) ?: child
            },
        )
    },
)

/** Depth-first walk over every component of a rule. */
private fun List<RuleComponent>.allComponents(): List<RuleComponent> = flatMap { component ->
    listOf(component) + component.conditions.allComponents() + component.children.allComponents() +
        component.branches.flatMap { it.conditions.allComponents() + it.children.allComponents() }
}

private fun iconOrDefault(value: String): Ic = runCatching { Ic.valueOf(value) }.getOrDefault(Ic.Bolt)

private fun uuidFor(id: Long): String = LegacyCommandMigration.uuidForLegacyId(id)
