package com.flowglass.demo.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.flowglass.demo.system.RootInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One command. Auto commands have triggers + conditions + actions;
 * one-tap commands only have actions and are fired manually.
 */
data class Cmd(
    val id: Long,
    val auto: Boolean,
    val ic: Ic,
    val title: String,
    val desc: String,
    val triggers: List<CommandItem> = emptyList(),
    val conditions: List<CommandItem> = emptyList(),
    val actions: List<CommandItem>,
    val category: Int = 0,
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val lastRun: String = "从未",
    val descriptionEdited: Boolean = true,
    /** TriggerMatch: ANY keeps the old behavior; ALL requires every trigger. */
    val triggerMatch: com.flowglass.engine.MatchSpec =
        com.flowglass.engine.MatchSpec(mode = com.flowglass.engine.MatchMode.ANY),
    val runMode: com.flowglass.engine.RunMode = com.flowglass.engine.RunMode.DROP,
) {
    val meta: String
        get() = if (auto) "触发器 ${triggers.size} · 条件 ${conditions.size} · 任务 ${actions.allItems().size}"
        else "任务 ${actions.allItems().size} 项"
}

private fun Cmd.automaticDescription(): String {
    fun nested(item: CommandItem) = item.conditions.size + item.children.size +
        item.branches.sumOf { it.children.size + it.conditions.size }
    val tasks = actions.joinToString(" → ") { ActionSummary.summarize(it, nested(it), open = true) }
        .ifEmpty { "请添加任务" }
    if (!auto) return tasks
    val trigger = triggers.joinToString(" 或 ") { ActionSummary.conditionText(it) }
        .let { if (it.isEmpty()) "请添加触发器" else "$it 时" }
    val condition = conditions.joinToString(" 且 ") { ActionSummary.conditionText(it) }
        .let { if (it.isEmpty()) "" else "，若 $it" }
    return "$trigger$condition，则 $tasks"
}

/** Rewrites a generated description after the summary wording changed; edited text is kept. */
private fun Cmd.withRefreshedDescription(): Cmd {
    if (descriptionEdited) return this
    val fresh = automaticDescription()
    return if (fresh == desc) this else copy(desc = fresh)
}

data class RunLog(val id: Long, val title: String, val reason: String, val time: String, val ok: Boolean, val ic: Ic,
    val detail: String = "", val steps: List<com.flowglass.demo.system.exec.StepResult> = emptyList(),
    val skipped: Boolean = false, val at: Long = 0, val durationMs: Long = 0)

data class MenuReq(val cmdId: Long, val auto: Boolean, val anchor: Offset)
data class UndoInfo(
    val cmd: Cmd,
    val index: Int,
    val label: String = cmd.title,
    /** Restores the whole command snapshot instead of reinserting a deleted command. */
    val restoreCommand: Boolean = false,
    /** Optional undo-bar text; delete keeps the existing wording. */
    val message: String? = null,
    /**
     * Local inverse of one delete or move, applied to the live command. Undo must not restore the
     * snapshot taken before the action, or edits made while the bar is visible are lost.
     */
    val patch: ((Cmd) -> Cmd)? = null,
    /** Canonical rule removed together with a deleted command, so undo can restore it. */
    val engineRule: com.flowglass.engine.Rule? = null,
    val presentation: com.flowglass.engine.RulePresentation? = null,
)

/** Where one node lived, so a delete or move can be reversed in the current tree. */
private data class NodeSlot(val parentId: Long?, val branch: Int?, val index: Int)

private fun List<CommandItem>.locateStep(id: Long, parentId: Long? = null, branch: Int? = null): NodeSlot? {
    forEachIndexed { index, item ->
        if (item.id == id) return NodeSlot(parentId, branch, index)
        item.children.locateStep(id, item.id, null)?.let { return it }
        item.branches.forEachIndexed { bi, b -> b.children.locateStep(id, item.id, bi)?.let { found -> return found } }
    }
    return null
}

private fun List<CommandItem>.locateCondition(id: Long, parentId: Long? = null): NodeSlot? {
    forEach { item ->
        item.conditions.forEachIndexed { ci, child -> if (child.id == id) return NodeSlot(item.id, null, ci) }
        item.branches.forEachIndexed { bi, b ->
            b.conditions.forEachIndexed { ci, child -> if (child.id == id) return NodeSlot(item.id, bi, ci) }
        }
        item.children.locateCondition(id, item.id)?.let { return it }
        item.branches.forEachIndexed { bi, b -> b.children.locateCondition(id, item.id)?.let { found -> return found } }
    }
    return null
}

private fun insertStep(items: List<CommandItem>, slot: NodeSlot, node: CommandItem): List<CommandItem> {
    if (slot.parentId == null) {
        if (slot.branch != null) return items
        return items.toMutableList().apply { add(slot.index.coerceIn(0, size), node) }
    }
    return items.updateItem(slot.parentId) { parent ->
        if (slot.branch == null) {
            parent.copy(children = parent.children.toMutableList().apply { add(slot.index.coerceIn(0, size), node) })
        } else {
            parent.copy(branches = parent.branches.mapIndexed { index, branch ->
                if (index != slot.branch) branch
                else branch.copy(children = branch.children.toMutableList().apply { add(slot.index.coerceIn(0, size), node) })
            })
        }
    }
}

private fun insertCondition(items: List<CommandItem>, slot: NodeSlot, node: CommandItem): List<CommandItem> =
    items.updateItem(slot.parentId ?: return items) { parent ->
        if (slot.branch == null) {
            parent.copy(conditions = parent.conditions.toMutableList().apply { add(slot.index.coerceIn(0, size), node) })
        } else {
            parent.copy(branches = parent.branches.mapIndexed { index, branch ->
                if (index != slot.branch) branch
                else branch.copy(conditions = branch.conditions.toMutableList().apply { add(slot.index.coerceIn(0, size), node) })
            })
        }
    }

private fun Cmd.withItems(section: CommandSection, items: List<CommandItem>): Cmd = when (section) {
    CommandSection.TRIGGERS -> copy(triggers = items)
    CommandSection.CONDITIONS -> copy(conditions = items)
    CommandSection.ACTIONS -> copy(actions = items)
}

private var nextId = 1000L
@Synchronized
fun newId(): Long {
    check(nextId < Long.MAX_VALUE) { "标识已用尽" }
    return nextId++
}

@Synchronized
private fun reserveIds(saved: PersistedAppState) {
    val maximum = ((saved.autoCmds + saved.oneTaps).flatMap { cmd ->
        listOf(cmd.id) + (cmd.triggers + cmd.conditions).allItems().map { it.id } +
            cmd.actions.allItems().map { it.id }
    } + saved.logs.map { it.id } + saved.logs.flatMap { log -> log.steps.map { it.itemId } }).maxOrNull() ?: 0L
    require(maximum < Long.MAX_VALUE) { "存储标识超出范围" }
    nextId = maxOf(nextId, maximum + 1)
}

private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
private fun nowEpochMs(): Long = System.currentTimeMillis()

/** 全部 / 已开启 plus every trigger category the component registry declares. */
val AutoBaseFilters = listOf("全部", "已开启")
fun autoFilters(): List<String> = AutoBaseFilters + CommandCatalog.categories(CommandSection.TRIGGERS)

/** Category of a rule, read from its first trigger's metadata; falls back to the saved bucket. */
internal fun Cmd.categoryName(): String? =
    triggers.firstOrNull()?.typeId
        ?.let { CommandCatalog.meta(it)?.category }
        ?: CommandCatalog.legacyCategoryName(category)

@Stable
class AppState internal constructor(saved: PersistedAppState?) {
    constructor() : this(null)
    private var executionEngine: com.flowglass.demo.system.exec.ExecutionEngine? = null
    fun bindExecutionContext(context: android.content.Context) =
        bindExecutionEngine(com.flowglass.demo.system.exec.ExecutionRuntime.engine(context))

    /** Persisted rule locals plus globals, keyed by name; the trigger host evaluates conditions before a run. */
    internal fun persistedVariablesFor(engineRuleId: String): Map<String, Any?> {
        val rule = engineRules.firstOrNull { it.id == engineRuleId } ?: return emptyMap()
        val values = LinkedHashMap<String, Any?>()
        // Globals first: a same-named local shadows them, matching RunScopes.flattened().
        for ((name, json) in engineGlobals) {
            val type = com.flowglass.engine.VariableType.fromStorage(engineGlobalMeta[name]?.type) ?: continue
            // A value that does not match its declared type must not abort trigger dispatch for every rule.
            values[name] = runCatching { com.flowglass.engine.VariableValues.fromJson(json, type) }.getOrNull() ?: continue
        }
        for (local in rule.locals) {
            val type = com.flowglass.engine.VariableType.fromStorage(local.type) ?: continue
            values[local.name] = runCatching { com.flowglass.engine.VariableValues.fromJson(local.initial, type) }
                .getOrNull() ?: continue
        }
        return values
    }

    internal fun bindExecutionEngine(engine: com.flowglass.demo.system.exec.ExecutionEngine) {
        executionEngine = engine
        // Async run_rule starts a normal AppState run: its own log row, job, stop button and budget.
        engine.ruleLookup = { target ->
            (autoCmds + oneTaps).firstOrNull { it.title.trim() == target.trim() }
        }
        engine.globalsProvider = { engineGlobals to engineGlobalMeta }
        engine.ruleResolver = { cmdId ->
            val ruleId = engineUi.presentation.entries.firstOrNull { it.value.legacyId == cmdId }?.key
            engineRules.firstOrNull { it.id == ruleId }
        }
        engine.ruleLauncher = { target, reason, vars ->
            val cmd = (autoCmds + oneTaps).firstOrNull { it.title.trim() == target.trim() }
            if (cmd == null) com.flowglass.engine.ActionOutcome(
                com.flowglass.engine.StepStatus.FAILURE, "找不到指令：$target",
            ) else if (executing.containsKey(cmd.id)) com.flowglass.engine.ActionOutcome(
                com.flowglass.engine.StepStatus.FAILURE, "「$target」正在运行，已跳过本次调用",
            ) else {
                com.flowglass.demo.system.exec.ExecutionRuntime.scope.launch {
                    execute(cmd, com.flowglass.demo.system.exec.ExecutionRuntime.scope, reason, vars)
                }
                com.flowglass.engine.ActionOutcome(com.flowglass.engine.StepStatus.SUCCESS, "已发起「$target」")
            }
        }
        engine.documentEpoch = { documentEpoch }
        engine.variableWriter = { ruleId, ruleWrites, globalWrites, epoch ->
            com.flowglass.demo.system.exec.ExecutionRuntime.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (epoch == documentEpoch) applyVariableWrites(ruleId, ruleWrites, globalWrites)
            }
        }
        engine.variableDefinitionWriter = { ruleId, locals, globals, epoch ->
            com.flowglass.demo.system.exec.ExecutionRuntime.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (epoch == documentEpoch) applyVariableDefinitions(ruleId, locals, globals)
            }
        }
    }

    private fun applyVariableDefinitions(
        ruleId: String,
        locals: List<com.flowglass.engine.VariableDefinition>,
        globals: List<com.flowglass.engine.VariableDefinition>,
    ) {
        if (locals.isNotEmpty()) {
            engineRules = engineRules.map { rule ->
                if (rule.id != ruleId) rule else {
                    var definitions = rule.locals
                    for (definition in locals) {
                        val index = definitions.indexOfFirst { it.name == definition.name }
                        val previous = definitions.getOrNull(index)
                        val initial = when {
                            previous != null -> previous.initial
                            definition.initial is kotlinx.serialization.json.JsonElement ->
                                definition.initial as kotlinx.serialization.json.JsonElement
                            else -> com.flowglass.engine.VariableValues.toJson(definition.initial)
                        }
                        val next = com.flowglass.engine.LocalVariable(
                            name = definition.name,
                            type = definition.type.storageKey,
                            initial = initial,
                            resetEachRun = definition.resetEachRun,
                            note = previous?.note.orEmpty(),
                        )
                        definitions = if (index >= 0) definitions.toMutableList().also { it[index] = next }
                        else definitions + next
                    }
                    rule.copy(locals = definitions)
                }
            }
        }
        if (globals.isNotEmpty()) {
            engineGlobalMeta = engineGlobalMeta + globals.associate { definition ->
                definition.name to (engineGlobalMeta[definition.name]?.copy(
                    type = definition.type.storageKey,
                ) ?: com.flowglass.engine.GlobalVariableMeta(definition.type.storageKey))
            }
        }
    }

    private fun applyVariableWrites(ruleId: String, ruleWrites: Map<String, Any?>, globalWrites: Map<String, Any?>) {
        if (ruleWrites.isNotEmpty()) {
            engineRules = engineRules.map { rule ->
                if (rule.id != ruleId) rule else {
                    var locals = rule.locals
                    for ((name, value) in ruleWrites) {
                        val element = com.flowglass.engine.VariableValues.toJson(value)
                        val index = locals.indexOfFirst { it.name == name }
                        locals = if (index >= 0) locals.toMutableList().also {
                            it[index] = it[index].copy(initial = element)
                        } else locals + com.flowglass.engine.LocalVariable(
                            name = name, type = com.flowglass.engine.VariableValues.inferType(value).storageKey,
                            initial = element,
                        )
                    }
                    rule.copy(locals = locals)
                }
            }
        }
        if (globalWrites.isNotEmpty()) {
            engineGlobals = engineGlobals + globalWrites.mapValues {
                com.flowglass.engine.VariableValues.toJson(it.value)
            }
            engineGlobalMeta = engineGlobalMeta + globalWrites.keys.associateWith { name ->
                engineGlobalMeta[name] ?: com.flowglass.engine.GlobalVariableMeta()
            }
        }
    }

    var editing by mutableStateOf<Long?>(null)
    /** 参数页打开中的指令 id；与 actionEditing 同时非空时显示该步骤的参数页。 */
    var actionEditingCmd by mutableStateOf<Long?>(null)
    /** 参数页正在编辑的步骤 id。 */
    var actionEditing by mutableStateOf<Long?>(null)
    /** 统一的组件参数草稿；null 表示参数页关闭。 */
    var componentDraft by mutableStateOf<ComponentDraft?>(null)
    /** 指令编辑页里正在配置输出变量的动作 id；null 表示未打开。 */
    var outputItemId by mutableStateOf<Long?>(null)
    var storageError by mutableStateOf<String?>(null)
    /** 编辑页内的即时提示（例如未通过校验时拒绝启用）。 */
    var editorNotice by mutableStateOf<String?>(null)
    /** File-picker payloads outlive the Activity but must not enter the saved-state Bundle. */
    var pendingExportText by mutableStateOf<String?>(null)
    var pendingImportJson by mutableStateOf<String?>(null)
    var pendingShareJson by mutableStateOf<String?>(null)
    var sharingCmdId by mutableStateOf<Long?>(null)
    internal var engineRules by mutableStateOf(saved?.engineRules.orEmpty())
    internal var engineGlobals by mutableStateOf(saved?.engineGlobals.orEmpty())
    internal var engineGlobalMeta by mutableStateOf(saved?.engineGlobalMeta.orEmpty())
    internal var engineUi by mutableStateOf(saved?.engineUi ?: com.flowglass.engine.RuleUiMetadata())
    /** 当前块编辑页路径；空表示指令根页。 */
    var blockPath by mutableStateOf<List<Long>>(emptyList())

    fun openEditor(id: Long) {
        if (find(id) != null) {
            editing = id
            blockPath = emptyList()
        }
    }

    /** True when [id] can be opened from the current page as a block page. */
    fun canOpenBlock(id: Long): Boolean {
        val cmd = editing?.let(::find) ?: return false
        val node = cmd.actions.findActionNode(id) ?: return false
        if (blockKind(node) !in BlockPageKinds) return false
        val parentId = blockPath.lastOrNull() ?: return cmd.actions.any { it.id == id }
        val parent = cmd.actions.findActionNode(parentId) ?: return false
        return (parent.children + parent.branches.flatMap { it.children }).any { it.id == id }
    }

    fun openBlock(id: Long) {
        if (canOpenBlock(id)) blockPath = blockPath + id
    }

    fun backBlock() { blockPath = blockPath.dropLast(1) }

    fun gotoBlock(index: Int) { blockPath = blockPath.take((index + 1).coerceAtLeast(0)) }

    /** Path from the command root to [blockId], or null when it is not an openable block. */
    fun blockPathTo(blockId: Long): List<Long>? {
        val cmd = editing?.let(::find) ?: return null
        fun walk(items: List<CommandItem>, ancestors: List<Long>): List<Long>? {
            for (item in items) {
                val isBlock = blockKind(item) in BlockPageKinds
                val next = if (isBlock) ancestors + item.id else ancestors
                if (isBlock && item.id == blockId) return next
                walk(item.children, next)?.let { return it }
                for (branch in item.branches) {
                    walk(branch.children, next)?.let { return it }
                }
            }
            return null
        }
        return walk(cmd.actions, emptyList())
    }

    /** Opens the page that owns [blockId], including a nested block from a validation row. */
    fun openBlockAt(blockId: Long) {
        val path = blockPathTo(blockId) ?: return
        blockPath = validBlockPath(path)
    }

    /** Drops any path level whose node is missing, wrong kind or no longer a direct child. */
    fun validBlockPath(path: List<Long>): List<Long> {
        val cmd = editing?.let(::find) ?: return emptyList()
        var items = cmd.actions
        val out = mutableListOf<Long>()
        for (id in path) {
            val node = items.firstOrNull { it.id == id } ?: break
            if (blockKind(node) !in BlockPageKinds) break
            out += id
            items = node.children + node.branches.flatMap { it.children }
        }
        return out
    }

    private fun blockKind(item: CommandItem): com.flowglass.engine.BlockKind? =
        item.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it)?.block }

    companion object {
        private val BlockPageKinds = setOf(
            com.flowglass.engine.BlockKind.IF,
            com.flowglass.engine.BlockKind.LOOP,
            com.flowglass.engine.BlockKind.WAIT,
        )
    }

    fun closeEditor() {
        editing?.let { id ->
            update(id) { cmd ->
                if (cmd.title.isBlank()) cmd.copy(title = if (cmd.auto) "未命名指令" else "未命名一键指令") else cmd
            }
        }
        editing = null
        blockPath = emptyList()
        actionEditing = null
        actionEditingCmd = null
        componentDraft = null
        outputItemId = null
    }
    fun setTitle(id: Long, text: String) = update(id) { it.copy(title = text) }
    fun setDescription(id: Long, text: String) = update(id) { it.copy(desc = text, descriptionEdited = true) }
    fun setIcon(id: Long, ic: Ic) = update(id) { it.copy(ic = ic) }
    /** Appends a step inside a container (if then / loop body / branch body). */
    fun addChildItem(cmdId: Long, parentId: Long, branch: Int?, option: CommandOption) =
        editActions(cmdId) { items ->
            items.updateItem(parentId) { parent ->
                val child = CommandItem(newId(), option.name, option.defaultParameter, option.ic, option.typeId)
                if (branch == null) parent.copy(children = parent.children + child)
                else parent.copy(branches = parent.branches.mapIndexed { index, b ->
                    if (index == branch) b.copy(children = b.children + child) else b
                })
            }
        }

    /** Adds a condition to if/while/wait; [branch] null means the block's own condition list. */
    fun addConditionItem(cmdId: Long, parentId: Long, branch: Int?, option: CommandOption) =
        editActions(cmdId) { items ->
            items.updateItem(parentId) { parent ->
                val condition = CommandItem(newId(), option.name, option.defaultParameter, option.ic, option.typeId)
                if (branch == null) parent.copy(conditions = parent.conditions + condition)
                else parent.copy(branches = parent.branches.mapIndexed { index, b ->
                    if (index == branch) b.copy(conditions = b.conditions + condition) else b
                })
            }
        }

    /** Appends an else-if (with its own condition) or a plain else branch. */
    fun addBranch(cmdId: Long, parentId: Long, withCondition: Boolean) = editActions(cmdId) { items ->
        items.updateItem(parentId) { parent ->
            val branch = CommandBranch()
            parent.copy(branches = parent.branches + branch)
        }
    }

    fun removeBranch(cmdId: Long, parentId: Long, branch: Int) = editActions(cmdId) { items ->
        items.updateItem(parentId) { parent ->
            parent.copy(branches = parent.branches.filterIndexed { index, _ -> index != branch })
        }
    }

    private fun editActions(cmdId: Long, change: (List<CommandItem>) -> List<CommandItem>) =
        update(cmdId) { cmd -> cmd.copy(actions = change(cmd.actions)).let { updated ->
            if (updated.descriptionEdited) updated else updated.copy(desc = updated.automaticDescription())
        } }

    fun addItem(cmdId: Long, section: CommandSection, option: CommandOption) {
        editItems(cmdId, section) { it + newCommandItem(cmdId, option) }
        if (section == CommandSection.TRIGGERS) update(cmdId) {
            if (it.auto && it.triggers.size == 1) it.copy(category = option.category) else it
        }
    }

    /** 把旧版/未知步骤换成目录里的真实动作，保留它的失败策略。 */
    fun replaceItem(cmdId: Long, section: CommandSection, itemId: Long, option: CommandOption) =
        editItems(cmdId, section) { items ->
            val fresh = newCommandItem(cmdId, option)
            items.map {
                if (it.id == itemId) it.copy(
                    name = option.name,
                    ic = option.ic,
                    parameter = option.defaultParameter,
                    typeId = option.typeId,
                    outputs = fresh.outputs,
                ) else it
            }
        }

    fun setItemOnError(cmdId: Long, section: CommandSection, itemId: Long, onError: com.flowglass.engine.OnError) =
        editItems(cmdId, section) { items -> items.updateItem(itemId) { it.copy(onError = onError) } }

    fun setItemParameter(cmdId: Long, section: CommandSection, itemId: Long, text: String) {
        // One-tap rules have no top-level condition list; block conditions still live in actions.
        val nestedCondition = section == CommandSection.CONDITIONS &&
            find(cmdId)?.actions?.findItem(itemId) != null
        if (nestedCondition) {
            editActions(cmdId) { items -> items.updateItem(itemId) { it.copy(parameter = text) } }
        } else {
            editItems(cmdId, section) { items -> items.updateItem(itemId) { it.copy(parameter = text) } }
        }
    }

    private fun hasOutputPage(meta: com.flowglass.engine.ComponentMeta): Boolean =
        meta.kind != com.flowglass.engine.ComponentKind.CONDITION &&
            com.flowglass.engine.OutputCatalog.allOutputs(meta).isNotEmpty()

    private fun commandOutputNames(cmdId: Long): MutableSet<String> {
        val names = linkedSetOf<String>()
        names += com.flowglass.engine.BuiltInVariables.all.map { it.name }
        val cmd = find(cmdId) ?: return names
        names += localsOf(cmdId).map { it.name }
        names += engineGlobals.keys
        fun walk(items: List<CommandItem>) {
            for (item in items) {
                names += item.outputs.map { it.name }
                walk(item.conditions)
                walk(item.children)
                item.branches.forEach { branch -> walk(branch.conditions); walk(branch.children) }
            }
        }
        walk(cmd.triggers + cmd.conditions + cmd.actions)
        return names
    }

    private fun newCommandItem(cmdId: Long, option: CommandOption): CommandItem {
        val meta = option.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it) }
        val outputs = if (meta != null && hasOutputPage(meta)) {
            com.flowglass.engine.OutputCatalog.bindingsForNew(meta, commandOutputNames(cmdId))
        } else emptyList()
        return CommandItem(newId(), option.name, option.defaultParameter, option.ic, option.typeId, outputs = outputs)
    }

    /** Opens the shared parameter page for an existing component without touching the command yet. */
    fun openComponentEditor(
        cmdId: Long,
        section: CommandSection,
        item: CommandItem,
        parentId: Long? = null,
        branch: Int? = null,
    ): Boolean {
        val meta = item.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it) }
        if (meta?.block in BlockPageKinds) {
            openBlock(item.id)
            return false
        }
        if (meta == null || (meta.params.isEmpty() && !hasOutputPage(meta))) {
            editorNotice = if (meta == null) "未知组件" else "该组件没有可编辑参数或输出"
            return false
        }
        componentDraft = ComponentDraft(cmdId, section, item, item.copy(), parentId, branch)
        if (section == CommandSection.ACTIONS) {
            actionEditingCmd = cmdId
            actionEditing = item.id
        }
        return true
    }

    /** Starts a new component as a private draft; [saveComponentDraft] is what inserts it. */
    fun startComponentDraft(
        cmdId: Long,
        section: CommandSection,
        option: CommandOption,
        parentId: Long? = null,
        branch: Int? = null,
    ): ComponentDraft {
        val item = newCommandItem(cmdId, option)
        val draft = ComponentDraft(
            cmdId, section, item, original = null, parentId = parentId, branch = branch,
            category = option.category,
        )
        componentDraft = draft
        actionEditingCmd = null
        actionEditing = null
        return draft
    }

    fun updateComponentDraft(item: CommandItem) {
        componentDraft = componentDraft?.copy(item = item)
    }

    /** Replaces only the output bindings of the open draft; parameters stay untouched. */
    fun updateComponentOutputs(outputs: List<com.flowglass.engine.OutputBinding>) {
        componentDraft = componentDraft?.let { draft -> draft.copy(item = draft.item.copy(outputs = outputs)) }
    }

    /** Block pages edit a saved item directly, not a draft. */
    fun updateBlockOutputs(cmdId: Long, itemId: Long, outputs: List<com.flowglass.engine.OutputBinding>) {
        editActions(cmdId) { items -> items.updateItem(itemId) { it.copy(outputs = outputs) } }
    }

    /** 输出变量页写回：动作和触发器都能改；条件没有输出，不会走到这里。 */
    fun updateItemOutputs(
        cmdId: Long,
        section: CommandSection,
        itemId: Long,
        outputs: List<com.flowglass.engine.OutputBinding>,
    ) {
        if (section == CommandSection.ACTIONS) {
            editActions(cmdId) { items -> items.updateItem(itemId) { it.copy(outputs = outputs) } }
        } else {
            editItems(cmdId, section) { items -> items.updateItem(itemId) { it.copy(outputs = outputs) } }
        }
    }

    /** Adds a component without opening the parameter page (blocks and parameterless items). */
    fun addComponentDirect(
        cmdId: Long,
        section: CommandSection,
        option: CommandOption,
        parentId: Long? = null,
        branch: Int? = null,
    ): Long {
        val item = newCommandItem(cmdId, option)
        insertDraftItem(ComponentDraft(
            cmdId, section, item, original = null, parentId = parentId, branch = branch,
            category = option.category,
        ))
        return item.id
    }

    fun cancelComponentDraft() {
        componentDraft = null
        actionEditing = null
        actionEditingCmd = null
    }

    fun saveComponentDraft(): Boolean {
        val draft = componentDraft ?: return false
        if (draft.isNew) insertDraftItem(draft) else replaceDraftItem(draft)
        componentDraft = null
        actionEditing = null
        actionEditingCmd = null
        return true
    }

    private fun insertDraftItem(draft: ComponentDraft) {
        val item = draft.item
        when (draft.section) {
            CommandSection.TRIGGERS -> {
                editItems(draft.cmdId, draft.section) { it + item }
                draft.category?.let { category ->
                    update(draft.cmdId) { command ->
                        if (command.auto && command.triggers.size == 1) command.copy(category = category) else command
                    }
                }
            }
            CommandSection.CONDITIONS -> if (draft.parentId == null) {
                editItems(draft.cmdId, draft.section) { it + item }
            } else {
                editActions(draft.cmdId) { items ->
                    items.updateItem(draft.parentId) { parent ->
                        if (draft.branch == null) parent.copy(conditions = parent.conditions + item)
                        else parent.copy(branches = parent.branches.mapIndexed { index, branch ->
                            if (index == draft.branch) branch.copy(conditions = branch.conditions + item) else branch
                        })
                    }
                }
            }
            CommandSection.ACTIONS -> if (draft.parentId == null) {
                editActions(draft.cmdId) { it + item }
            } else {
                editActions(draft.cmdId) { items ->
                    items.updateItem(draft.parentId) { parent ->
                        if (draft.branch == null) parent.copy(children = parent.children + item)
                        else parent.copy(branches = parent.branches.mapIndexed { index, branch ->
                            if (index == draft.branch) branch.copy(children = branch.children + item) else branch
                        })
                    }
                }
            }
        }
    }

    private fun replaceDraftItem(draft: ComponentDraft) {
        if (draft.section == CommandSection.ACTIONS || draft.parentId != null) {
            editActions(draft.cmdId) { items -> items.updateItem(draft.item.id) { draft.item } }
        } else {
            editItems(draft.cmdId, draft.section) { items -> items.updateItem(draft.item.id) { draft.item } }
        }
    }

    fun toggleCollapsed(cmdId: Long, itemId: Long) =
        editActions(cmdId) { items -> items.updateItem(itemId) { it.copy(collapsed = !it.collapsed) } }

    internal fun ruleIdFor(cmdId: Long): String? =
        engineUi.presentation.entries.firstOrNull { it.value.legacyId == cmdId }?.key

    /**
     * A command created in this process has no canonical rule until the first save, so local
     * variables cannot be attached to it. Materialize the same rule/presentation the mapper would
     * create, keyed by the stable legacy id, before editing its locals.
     */
    private fun ensureEngineRule(cmd: Cmd): String {
        ruleIdFor(cmd.id)?.let { return it }
        val ruleId = com.flowglass.engine.LegacyCommandMigration.uuidForLegacyId(cmd.id)
        if (engineRules.none { it.id == ruleId }) {
            engineRules = engineRules + com.flowglass.engine.Rule(
                id = ruleId,
                name = cmd.title,
                description = cmd.desc,
                type = if (cmd.auto) com.flowglass.engine.RuleType.AUTO else com.flowglass.engine.RuleType.MANUAL,
                enabled = cmd.enabled,
                runMode = cmd.runMode,
                triggerMatch = cmd.triggerMatch,
            )
        }
        if (engineUi.presentation[ruleId] == null) {
            engineUi = engineUi.copy(
                presentation = engineUi.presentation + (ruleId to presentationFor(cmd)),
            )
        }
        return ruleId
    }

    private fun presentationFor(cmd: Cmd): com.flowglass.engine.RulePresentation {
        val items = (cmd.triggers + cmd.conditions + cmd.actions).allItems()
        return com.flowglass.engine.RulePresentation(
            category = cmd.category,
            icon = cmd.ic.name,
            pinned = cmd.pinned,
            lastRun = cmd.lastRun,
            descriptionEdited = cmd.descriptionEdited,
            legacyId = cmd.id,
            items = items.associate { it.id to com.flowglass.engine.ComponentPresentation(it.id, it.ic.name) },
            collapsed = items.filter { it.collapsed }.map { it.id },
        )
    }

    fun localsOf(cmdId: Long): List<com.flowglass.engine.LocalVariable> =
        engineRules.firstOrNull { it.id == ruleIdFor(cmdId) }?.locals.orEmpty()

    fun allCommands(): List<Cmd> = autoCmds + oneTaps

    fun renameVariable(ownerId: Long?, oldName: String, newName: String, scope: com.flowglass.engine.VariableScope) {
        if (oldName == newName) return
        if (scope == com.flowglass.engine.VariableScope.BUILTIN) return
        val ownerRuleId = if (scope == com.flowglass.engine.VariableScope.LOCAL) {
            ownerId?.let(::ruleIdFor) ?: return
        } else null
        // Rewrite only what actually resolves to this definition: a local belongs to one rule, a
        // global is shadowed by a same-named local, and explicit `rule.`/`global.` prefixes must
        // follow the rename even when the bare form does not point at it.
        for (list in arrayOf(autoCmds, oneTaps)) {
            for (index in list.indices) {
                val cmd = list[index]
                val ruleId = ruleIdFor(cmd.id)
                val shadowed = engineRules.firstOrNull { it.id == ruleId }
                    ?.locals.orEmpty().any { it.name == oldName }
                val pairs = when (scope) {
                    com.flowglass.engine.VariableScope.LOCAL -> if (ruleId == ownerRuleId) {
                        listOf("{rule.$oldName}" to "{rule.$newName}", "{$oldName}" to "{$newName}")
                    } else emptyList()
                    com.flowglass.engine.VariableScope.GLOBAL -> buildList {
                        add("{global.$oldName}" to "{global.$newName}")
                        if (!shadowed) add("{$oldName}" to "{$newName}")
                    }
                    else -> emptyList()
                }
                if (pairs.isNotEmpty()) list[index] = renameTokens(cmd, pairs)
            }
        }
        if (ownerRuleId != null) {
            engineRules = engineRules.map { rule ->
                if (rule.id != ownerRuleId) rule else rule.copy(
                    locals = rule.locals.map { if (it.name == oldName) it.copy(name = newName) else it },
                )
            }
        } else if (scope == com.flowglass.engine.VariableScope.GLOBAL) {
            engineGlobals = engineGlobals.mapKeys { if (it.key == oldName) newName else it.key }
            engineGlobalMeta = engineGlobalMeta.mapKeys { if (it.key == oldName) newName else it.key }
        }
    }

    fun deleteVariable(ownerId: Long?, name: String, scope: com.flowglass.engine.VariableScope) {
        if (scope == com.flowglass.engine.VariableScope.LOCAL) {
            val ruleId = ownerId?.let(::ruleIdFor)
            engineRules = engineRules.map { rule ->
                if (ruleId != null && rule.id != ruleId) rule
                else rule.copy(locals = rule.locals.filterNot { it.name == name })
            }
        } else if (scope == com.flowglass.engine.VariableScope.GLOBAL) {
            removeGlobal(name)
        }
    }

    fun setVariableType(ownerId: Long?, name: String, type: com.flowglass.engine.VariableType, scope: com.flowglass.engine.VariableScope) {
        if (scope == com.flowglass.engine.VariableScope.LOCAL && ownerId != null) {
            val ruleId = ruleIdFor(ownerId) ?: return
            engineRules = engineRules.map { rule ->
                if (rule.id != ruleId) rule else rule.copy(
                    locals = rule.locals.map {
                        if (it.name == name) it.copy(type = type.storageKey) else it
                    },
                )
            }
        } else if (scope == com.flowglass.engine.VariableScope.GLOBAL) {
            val meta = engineGlobalMeta[name] ?: com.flowglass.engine.GlobalVariableMeta()
            engineGlobalMeta = engineGlobalMeta + (name to meta.copy(type = type.storageKey))
        }
    }

    fun setVariableNote(ownerId: Long?, name: String, note: String, scope: com.flowglass.engine.VariableScope) {
        if (scope == com.flowglass.engine.VariableScope.LOCAL && ownerId != null) {
            val ruleId = ruleIdFor(ownerId) ?: return
            engineRules = engineRules.map { rule ->
                if (rule.id != ruleId) rule else rule.copy(
                    locals = rule.locals.map { if (it.name == name) it.copy(note = note) else it },
                )
            }
        } else if (scope == com.flowglass.engine.VariableScope.GLOBAL) {
            val meta = engineGlobalMeta[name] ?: com.flowglass.engine.GlobalVariableMeta()
            engineGlobalMeta = engineGlobalMeta + (name to meta.copy(note = note))
        }
    }

    fun setVariableValue(ownerId: Long?, name: String, value: String, scope: com.flowglass.engine.VariableScope) {
        val rawType = if (scope == com.flowglass.engine.VariableScope.LOCAL) {
            val ruleId = ownerId?.let(::ruleIdFor) ?: return
            engineRules.firstOrNull { it.id == ruleId }?.locals?.firstOrNull { it.name == name }?.type
        } else if (scope == com.flowglass.engine.VariableScope.GLOBAL) {
            engineGlobalMeta[name]?.type
        } else null
        val type = com.flowglass.engine.VariableType.fromStorage(rawType)
            ?: com.flowglass.engine.VariableType.TEXT
        val parsed = runCatching { com.flowglass.engine.VariableValues.parse(value, type) }.getOrNull() ?: return
        val json = com.flowglass.engine.VariableValues.toJson(parsed)
        if (scope == com.flowglass.engine.VariableScope.LOCAL && ownerId != null) {
            val ruleId = ruleIdFor(ownerId) ?: return
            engineRules = engineRules.map { rule ->
                if (rule.id != ruleId) rule else rule.copy(
                    locals = rule.locals.map {
                        if (it.name == name) it.copy(initial = json) else it
                    },
                )
            }
        } else if (scope == com.flowglass.engine.VariableScope.GLOBAL) {
            engineGlobals = engineGlobals + (name to json)
            engineGlobalMeta = engineGlobalMeta + (name to (engineGlobalMeta[name] ?: com.flowglass.engine.GlobalVariableMeta()))
        }
    }

    private fun renameTokens(cmd: Cmd, tokens: List<Pair<String, String>>): Cmd {
        fun replace(text: String): String = tokens.fold(text) { acc, (from, to) -> acc.replace(from, to) }
        fun item(value: CommandItem): CommandItem = value.copy(
            parameter = replace(value.parameter),
            children = value.children.map(::item),
            conditions = value.conditions.map(::item),
            branches = value.branches.map { branch ->
                branch.copy(children = branch.children.map(::item), conditions = branch.conditions.map(::item))
            },
        )
        return cmd.copy(
            triggers = cmd.triggers.map(::item),
            conditions = cmd.conditions.map(::item),
            actions = cmd.actions.map(::item),
        )
    }

    fun setLocal(cmdId: Long, name: String, value: String, type: String = "text") {
        val id = ruleIdFor(cmdId) ?: run {
            editorNotice = "这条指令还没有存档，先返回列表再进来就能编辑变量"
            return
        }
        engineRules = engineRules.map { rule ->
            if (rule.id != id) rule else {
                val index = rule.locals.indexOfFirst { it.name == name }
                val previous = rule.locals.getOrNull(index)
                val variable = if (previous != null) {
                    previous.copy(type = type, initial = kotlinx.serialization.json.JsonPrimitive(value))
                } else {
                    com.flowglass.engine.LocalVariable(
                        name = name, type = type, initial = kotlinx.serialization.json.JsonPrimitive(value),
                    )
                }
                val locals = if (index >= 0) rule.locals.toMutableList().also { it[index] = variable }
                else rule.locals + variable
                rule.copy(locals = locals)
            }
        }
    }

    fun removeLocal(cmdId: Long, name: String) {
        val id = ruleIdFor(cmdId) ?: return
        engineRules = engineRules.map { rule ->
            if (rule.id != id) rule else rule.copy(locals = rule.locals.filterNot { it.name == name })
        }
    }

    fun setGlobal(name: String, value: String) {
        engineGlobals = engineGlobals + (name to kotlinx.serialization.json.JsonPrimitive(value))
        engineGlobalMeta = engineGlobalMeta + (name to (engineGlobalMeta[name] ?: com.flowglass.engine.GlobalVariableMeta()))
    }

    /** Creates a user variable from the management page without requiring the set-var action. */
    fun createVariable(
        scope: com.flowglass.engine.VariableScope,
        commandId: Long?,
        name: String,
        type: String,
        note: String,
        value: String,
    ) {
        val variableType = com.flowglass.engine.VariableType.fromStorage(type) ?: return
        val initial = com.flowglass.engine.VariableValues.toJson(
            com.flowglass.engine.VariableValues.parse(value, variableType),
        )
        when (scope) {
            com.flowglass.engine.VariableScope.OUTPUT -> return
            com.flowglass.engine.VariableScope.GLOBAL -> {
                engineGlobals = engineGlobals + (name to initial)
                engineGlobalMeta = engineGlobalMeta + (
                    name to com.flowglass.engine.GlobalVariableMeta(type = variableType.storageKey, note = note)
                )
            }
            com.flowglass.engine.VariableScope.LOCAL -> {
                val command = commandId?.let(::find) ?: return
                val ruleId = ensureEngineRule(command)
                engineRules = engineRules.map { rule ->
                    if (rule.id != ruleId) rule else rule.copy(
                        locals = rule.locals.filterNot { it.name == name } + com.flowglass.engine.LocalVariable(
                            name = name,
                            type = variableType.storageKey,
                            initial = initial,
                            note = note,
                        ),
                    )
                }
            }
            com.flowglass.engine.VariableScope.BUILTIN -> Unit
        }
    }

    fun removeGlobal(name: String) {
        engineGlobals = engineGlobals - name
        engineGlobalMeta = engineGlobalMeta - name
    }

    fun moveItem(cmdId: Long, section: CommandSection, itemId: Long, delta: Int) =
        editItems(cmdId, section) { items -> items.moveItem(itemId, delta) }

    /** Commits a long-press reorder in one write instead of one write per drag delta. */
    fun moveItemTo(cmdId: Long, section: CommandSection, itemId: Long, newIndex: Int) =
        editItems(cmdId, section) { items -> items.moveItemTo(itemId, newIndex) }

    private fun canHost(item: CommandItem): Boolean {
        val block = item.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it)?.block }
        return block == com.flowglass.engine.BlockKind.IF || block == com.flowglass.engine.BlockKind.LOOP
    }

    fun moveIntoPreviousBlock(cmdId: Long, itemId: Long) =
        editItems(cmdId, CommandSection.ACTIONS) { items -> items.moveIntoPreviousBlock(itemId, ::canHost) }

    fun moveOutOfBlock(cmdId: Long, itemId: Long) =
        editItems(cmdId, CommandSection.ACTIONS) { items -> items.moveOutOfBlock(itemId) }

    fun canMoveIntoPreviousBlock(cmdId: Long, itemId: Long): Boolean =
        find(cmdId)?.actions?.canMoveIntoPreviousBlock(itemId, ::canHost) == true

    fun removeItem(cmdId: Long, section: CommandSection, itemId: Long) =
        editItems(cmdId, section) { items -> items.removeItem(itemId) }

    /** Deletes a trigger or a top-level condition and keeps the command for the undo bar. */
    fun removeItemWithUndo(cmdId: Long, section: CommandSection, itemId: Long) {
        if (section == CommandSection.ACTIONS) {
            removeActionItemWithUndo(cmdId, itemId)
            return
        }
        val before = find(cmdId) ?: return
        val list = before.items(section)
        val index = list.indexOfFirst { it.id == itemId }
        val item = list.getOrNull(index) ?: return
        editItems(cmdId, section) { items -> items.removeItem(itemId) }
        undo = UndoInfo(
            before, index = -1, label = item.name,
            patch = { cmd ->
                val items = cmd.items(section).toMutableList()
                items.add(index.coerceIn(0, items.size), item)
                cmd.withItems(section, items)
            },
        )
    }

    /** Deletes an action step and keeps the whole command snapshot for the undo bar. */
    fun removeActionItemWithUndo(cmdId: Long, itemId: Long) {
        val before = find(cmdId) ?: return
        val item = before.actions.findItem(itemId) ?: return
        val slot = before.actions.locateStep(itemId) ?: return
        editActions(cmdId) { items -> items.removeItem(itemId) }
        undo = UndoInfo(
            before, index = -1, label = item.name,
            patch = { cmd -> cmd.copy(actions = insertStep(cmd.actions, slot, item)) },
        )
    }

    /** Removes a condition nested in a block (branch null) or in an else-if branch. */
    fun removeConditionItem(cmdId: Long, parentId: Long, branch: Int?, conditionId: Long) =
        editActions(cmdId) { items ->
            items.updateItem(parentId) { parent ->
                if (branch == null) parent.copy(conditions = parent.conditions.filterNot { it.id == conditionId })
                else parent.copy(branches = parent.branches.mapIndexed { index, b ->
                    if (index == branch) b.copy(conditions = b.conditions.filterNot { it.id == conditionId }) else b
                })
            }
        }

    /** Same as [removeConditionItem], with the whole command kept for the undo bar. */
    fun removeConditionItemWithUndo(cmdId: Long, parentId: Long, branch: Int?, conditionId: Long) {
        val before = find(cmdId) ?: return
        val parent = before.actions.findActionNode(parentId) ?: return
        val condition = if (branch == null) parent.conditions.firstOrNull { it.id == conditionId }
        else parent.branches.getOrNull(branch)?.conditions?.firstOrNull { it.id == conditionId }
        if (condition == null) return
        val slot = before.actions.locateCondition(conditionId) ?: return
        removeConditionItem(cmdId, parentId, branch, conditionId)
        undo = UndoInfo(
            before, index = -1, label = condition.name,
            patch = { cmd -> cmd.copy(actions = insertCondition(cmd.actions, slot, condition)) },
        )
    }

    /**
     * Moves one action step between any two lists. Returns false for a no-op or invalid target
     * (self/descendant, non-container, bad branch or index) without writing state.
     */
    fun moveActionTo(
        cmdId: Long,
        sourceId: Long,
        targetParentId: Long?,
        targetBranch: Int?,
        targetIndex: Int,
    ): Boolean {
        val before = find(cmdId) ?: return false
        val source = before.actions.findActionNode(sourceId) ?: return false
        val origin = before.actions.locateStep(sourceId) ?: return false
        val moved = before.actions.moveActionTo(sourceId, targetParentId, targetBranch, targetIndex) ?: return false
        if (moved == before.actions) return false
        update(cmdId) { cmd ->
            val updated = cmd.copy(actions = moved)
            if (updated.descriptionEdited) updated else updated.copy(desc = updated.automaticDescription())
        }
        undo = UndoInfo(
            before, index = -1, label = source.name,
            message = "已移动「${source.name}」",
            patch = { cmd ->
                val reverted = cmd.actions.moveActionTo(sourceId, origin.parentId, origin.branch, origin.index)
                if (reverted != null) cmd.copy(actions = reverted) else cmd
            },
        )
        return true
    }

    /** Commits one drag result; [parentId] null means the top-level action list. */
    fun moveStepTo(cmdId: Long, parentId: Long?, branch: Int?, itemId: Long, newIndex: Int) =
        editActions(cmdId) { items ->
            if (parentId == null) items.moveItemTo(itemId, newIndex)
            else items.updateItem(parentId) { parent ->
                if (branch == null) parent.copy(children = parent.children.moveItemTo(itemId, newIndex))
                else parent.copy(branches = parent.branches.mapIndexed { index, b ->
                    if (index == branch) b.copy(children = b.children.moveItemTo(itemId, newIndex)) else b
                })
            }
        }

    private fun editItems(id: Long, section: CommandSection, change: (List<CommandItem>) -> List<CommandItem>) =
        update(id) { cmd ->
            if (!cmd.auto && section != CommandSection.ACTIONS) cmd
            else {
                val items = change(cmd.items(section))
                if (items == cmd.items(section)) return@update cmd
                val updated = when (section) {
                    CommandSection.TRIGGERS -> cmd.copy(triggers = items)
                    CommandSection.CONDITIONS -> cmd.copy(conditions = items)
                    CommandSection.ACTIONS -> cmd.copy(actions = items)
                }
                if (updated.descriptionEdited) updated else updated.copy(desc = updated.automaticDescription())
            }
        }

    // engine
    var running by mutableStateOf(saved?.running ?: true)
        private set
    /** Epoch millis the engine last started; persisted so 运行时长 survives recreation. */
    var engineStartedAt by mutableLongStateOf(saved?.engineUi?.startedAt ?: 0)
        private set

    init { if (running && engineStartedAt == 0L) engineStartedAt = nowEpochMs() }

    fun setEngineRunning(on: Boolean) {
        if (running == on) return
        running = on
        if (on) engineStartedAt = nowEpochMs()
    }
    /** Bumped when a permission prompt returns; the trigger host re-evaluates blocked rules. */
    var permissionsEpoch by mutableIntStateOf(0)
    var mode by mutableIntStateOf(saved?.mode ?: 1)
    var accessibility by mutableStateOf(true)

    // lists
    val autoCmds = mutableStateListOf<Cmd>().apply { addAll(saved?.autoCmds.orEmpty().map { it.withRefreshedDescription() }) }
    val oneTaps = mutableStateListOf<Cmd>().apply { addAll(saved?.oneTaps.orEmpty().map { it.withRefreshedDescription() }) }
    var autoFilter by mutableIntStateOf(0)
    private val executionTokens = mutableMapOf<Long, MutableSet<Any>>()
    private val executionJobs = mutableMapOf<Long, MutableList<kotlinx.coroutines.Job>>()

    /**
     * 文档世代：恢复/导入会整份替换规则和变量。旧世代的任务即使已经算完，也不能把结果写进
     * 新文档（同名 ruleId/cmdId 会命中新规则），更不能追加历史或刷新 lastRun。
     */
    internal var documentEpoch = 0
        private set
    val executing = mutableStateMapOf<Long, Int>() // id -> 1 running, 2 done, 3 failed
    val executionMessages = mutableStateMapOf<Long, String>()
    val logs = mutableStateListOf<RunLog>().apply { addAll(saved?.logs.orEmpty()) }

    init {
        if (saved != null) {
            reserveIds(saved)
            normalizeLegacyBranches()
        }
    }

    /** Old if-blocks with else-if branches become nested if-blocks before any page opens. */
    private fun normalizeLegacyBranches() {
        fun normalize(cmd: Cmd): Cmd {
            val actions = cmd.actions.normalizeUiElseIfBranches(::newId)
            return if (actions == cmd.actions) cmd else cmd.copy(actions = actions)
        }
        for (list in arrayOf(autoCmds, oneTaps)) {
            for (index in list.indices) list[index] = normalize(list[index])
        }
    }

    // search (null = closed)
    var query by mutableStateOf<String?>(null)

    fun matches(c: Cmd): Boolean {
        val q = query?.trim().orEmpty()
        return q.isEmpty() || c.title.contains(q, true) || c.desc.contains(q, true) ||
            (c.triggers + c.conditions + c.actions).allItems().any { it.display.contains(q, true) }
    }

    fun itemById(cmdId: Long, id: Long): CommandItem? = find(cmdId)?.let {
        (it.triggers + it.conditions + it.actions).findItem(id)
    }

    /** Cancels a running execution; the job records no result, matching a manual stop. */
    fun stopExecution(cmdId: Long) {
        val cmd = find(cmdId) ?: return
        cancelExecution(cmdId)
        executing[cmdId] = 0
        executionMessages[cmdId] = "已手动停止"
        logs.add(0, RunLog(newId(), cmd.title, "手动停止", now(), false, cmd.ic, "已手动停止", emptyList(), skipped = true, at = nowEpochMs()))
        if (logs.size > 50) logs.removeAt(logs.lastIndex)
    }

    /** Cancels every active run without adding a manual-stop log row (used by RESTART). */
    fun cancelExecution(cmdId: Long) {
        executionJobs.remove(cmdId)?.forEach { it.cancel() }
        executionTokens.remove(cmdId)
        executing.remove(cmdId)
        executionMessages.remove(cmdId)
    }

    fun activeExecutionCount(cmdId: Long): Int = executionJobs[cmdId]?.size ?: 0

    // overlays
    var menu by mutableStateOf<MenuReq?>(null)
    var historyOpen by mutableStateOf(false)
    var renaming by mutableStateOf<Cmd?>(null)
    var undo by mutableStateOf<UndoInfo?>(null)

    // settings
    var settingsPage by mutableIntStateOf(0)
    var root by mutableStateOf<RootInfo?>(null)

    fun listFor(auto: Boolean): MutableList<Cmd> = if (auto) autoCmds else oneTaps

    fun find(id: Long): Cmd? = autoCmds.firstOrNull { it.id == id } ?: oneTaps.firstOrNull { it.id == id }

    /** Display order: pinned first, otherwise original order. */
    fun sorted(auto: Boolean): List<Cmd> = listFor(auto).sortedByDescending { it.pinned }

    /**
     * Replaces the whole in-memory document after an import/restore has been fully validated.
     * The restored ids are reserved before publishing: a backup from another install may carry
     * ids far above this process's counter, and the next allocation must not collide with them.
     */
    internal fun replaceWith(converted: PersistedAppState) {
        // 恢复屏障：先让旧世代失效并取消在途任务，再发布新文档。
        documentEpoch++
        executionJobs.values.flatten().forEach { it.cancel() }
        executionJobs.clear()
        executionTokens.clear()
        executing.clear()
        executionMessages.clear()
        undo = null
        historyOpen = false
        menu = null
        renaming = null
        root = null
        reserveIds(converted)
        autoCmds.clear(); autoCmds.addAll(converted.autoCmds)
        oneTaps.clear(); oneTaps.addAll(converted.oneTaps)
        logs.clear(); logs.addAll(converted.logs)
        engineRules = converted.engineRules
        engineUi = converted.engineUi
        engineGlobals = converted.engineGlobals
        engineGlobalMeta = converted.engineGlobalMeta
        running = converted.running
        mode = converted.mode
        engineStartedAt = converted.engineUi.startedAt
    }

    private fun update(id: Long, f: (Cmd) -> Cmd) {
        for (l in arrayOf(autoCmds, oneTaps)) {
            val i = l.indexOfFirst { it.id == id }
            if (i >= 0) { l[i] = f(l[i]); return }
        }
    }

    fun setEnabled(id: Long, on: Boolean) = update(id) { it.copy(enabled = on) }
    fun setTriggerMatch(id: Long, mode: com.flowglass.engine.MatchMode) =
        update(id) { it.copy(triggerMatch = it.triggerMatch.copy(mode = mode)) }
    fun setTriggerWindow(id: Long, seconds: Int) =
        update(id) { it.copy(triggerMatch = it.triggerMatch.copy(windowSeconds = seconds.coerceIn(1, 3600))) }
    fun setRunMode(id: Long, mode: com.flowglass.engine.RunMode) =
        update(id) { it.copy(runMode = mode) }
    fun togglePin(id: Long) = update(id) { it.copy(pinned = !it.pinned) }
    fun rename(id: Long, title: String) = update(id) { it.copy(title = title.ifBlank { it.title }) }

    fun duplicate(id: Long): Long? {
        val src = find(id) ?: return null
        val l = listFor(src.auto)
        val i = l.indexOf(src)
        fun copied(items: List<CommandItem>): List<CommandItem> = items.map { item ->
            item.copy(
                id = newId(),
                conditions = copied(item.conditions),
                children = copied(item.children),
                branches = item.branches.map { branch ->
                    branch.copy(conditions = copied(branch.conditions), children = copied(branch.children))
                },
            )
        }
        val copy = src.copy(
            id = newId(), title = "${src.title} 副本", pinned = false, lastRun = "从未",
            triggers = copied(src.triggers), conditions = copied(src.conditions), actions = copied(src.actions),
        )
        l.add(i + 1, copy)
        val sourceRule = ruleIdFor(src.id)?.let { rid -> engineRules.firstOrNull { it.id == rid } }
        val copyRuleId = com.flowglass.engine.LegacyCommandMigration.uuidForLegacyId(copy.id)
        // Build the canonical rule from the copied command, so component ids match the new tree
        // instead of pointing at the source command's nodes.
        val built = snapshot().toRuleDocument().rules.firstOrNull { it.id == copyRuleId }
            ?: com.flowglass.engine.Rule(
                id = copyRuleId,
                name = copy.title,
                description = copy.desc,
                type = if (copy.auto) com.flowglass.engine.RuleType.AUTO else com.flowglass.engine.RuleType.MANUAL,
                enabled = copy.enabled,
                runMode = copy.runMode,
                triggerMatch = copy.triggerMatch,
            )
        engineRules = engineRules.filterNot { it.id == copyRuleId } +
            built.copy(locals = sourceRule?.locals.orEmpty())
        if (engineUi.presentation[copyRuleId] == null) {
            engineUi = engineUi.copy(
                presentation = engineUi.presentation + (copyRuleId to presentationFor(copy)),
            )
        }
        return copy.id
    }

    fun delete(id: Long) {
        // Normalize the edited title before capturing the undo snapshot.
        if (editing == id) closeEditor()
        val src = find(id) ?: return
        val l = listFor(src.auto)
        val i = l.indexOf(src)
        l.removeAt(i)
        val ruleId = ruleIdFor(id)
        val removedRule = ruleId?.let { rid -> engineRules.firstOrNull { it.id == rid } }
        val removedPresentation = ruleId?.let { engineUi.presentation[it] }
        if (ruleId != null) {
            engineRules = engineRules.filterNot { it.id == ruleId }
            engineUi = engineUi.copy(presentation = engineUi.presentation - ruleId)
        }
        undo = UndoInfo(src, i, engineRule = removedRule, presentation = removedPresentation)
    }

    fun undoDelete() {
        val u = undo ?: return
        val patch = u.patch
        if (patch != null) {
            update(u.cmd.id) { patch(it).withRefreshedDescription() }
        } else if (u.restoreCommand) {
            update(u.cmd.id) { u.cmd }
        } else {
            val l = listFor(u.cmd.auto)
            l.add(u.index.coerceIn(0, l.size), u.cmd)
        }
        u.engineRule?.let { rule ->
            if (engineRules.none { it.id == rule.id }) engineRules = engineRules + rule
            u.presentation?.let { presentation ->
                engineUi = engineUi.copy(presentation = engineUi.presentation + (rule.id to presentation))
            }
        }
        undo = null
    }

    fun create(auto: Boolean): Cmd {
        val c = Cmd(newId(), auto, if (auto) Ic.Bolt else Ic.Tap,
            if (auto) "未命名指令" else "未命名一键指令",
            if (auto) "请添加触发器和任务" else "请添加任务",
            actions = emptyList(), enabled = !auto, descriptionEdited = false)
        listFor(auto).add(0, c)
        return c
    }

    fun execute(
        cmd: Cmd,
        scope: CoroutineScope,
        reason: String = "手动执行",
        variables: Map<String, Any?> = emptyMap(),
        readOnlyNames: Set<String> = emptySet(),
    ): kotlinx.coroutines.Job? {
        if (activeExecutionCount(cmd.id) > 0) return null
        return launchExecution(cmd, scope, reason, variables, allowParallel = false, readOnlyNames = readOnlyNames)
    }

    /** Trigger-host entry point; queue policy is owned by TriggerDispatcher. */
    fun executeWithPolicy(
        cmd: Cmd,
        scope: CoroutineScope,
        reason: String,
        variables: Map<String, Any?>,
        mode: com.flowglass.engine.RunMode,
        readOnlyNames: Set<String> = emptySet(),
    ): kotlinx.coroutines.Job? {
        val active = activeExecutionCount(cmd.id)
        return when (mode) {
            com.flowglass.engine.RunMode.DROP -> if (active > 0) null
                else launchExecution(cmd, scope, reason, variables, allowParallel = false, readOnlyNames = readOnlyNames)
            com.flowglass.engine.RunMode.QUEUE -> if (active > 0) null
                else launchExecution(cmd, scope, reason, variables, allowParallel = false, readOnlyNames = readOnlyNames)
            com.flowglass.engine.RunMode.RESTART -> {
                if (active > 0) cancelExecution(cmd.id)
                launchExecution(cmd, scope, reason, variables, allowParallel = false, readOnlyNames = readOnlyNames)
            }
            com.flowglass.engine.RunMode.PARALLEL -> if (active >= 3) null
                else launchExecution(cmd, scope, reason, variables, allowParallel = true, readOnlyNames = readOnlyNames)
        }
    }

    private fun launchExecution(
        cmd: Cmd,
        scope: CoroutineScope,
        reason: String,
        variables: Map<String, Any?>,
        allowParallel: Boolean = false,
        readOnlyNames: Set<String> = emptySet(),
    ): kotlinx.coroutines.Job {
        val engine = executionEngine
        val token = Any()
        val epoch = documentEpoch
        executionTokens.getOrPut(cmd.id) { mutableSetOf() }.add(token)
        executionMessages.remove(cmd.id)
        executing[cmd.id] = 1
        val job = com.flowglass.demo.system.exec.ExecutionRuntime.scope.launch {
            try {
                val result = try {
                    engine?.run(cmd, reason, variables, allowParallel, readOnlyNames) ?: com.flowglass.demo.system.exec.RunResult(
                        cmd.id, reason, listOf(com.flowglass.demo.system.exec.StepResult(
                            cmd.id, "执行环境检查", com.flowglass.demo.system.exec.StepStatus.FAILURE,
                            "执行环境未就绪", 0)), 0)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    internalFailure(cmd.id, reason, failure)
                }
                if (result.ignored) return@launch
                if (epoch != documentEpoch) return@launch
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    if (epoch != documentEpoch) return@withContext
                    executionMessages[cmd.id] = result.detail
                    executing[cmd.id] = if (result.ok) 2 else 3
                    val t = now()
                    update(cmd.id) { it.copy(lastRun = t) }
                    logs.add(0, RunLog(newId(), cmd.title, reason, t, result.ok, cmd.ic, result.detail, result.steps.toList(),
                        at = nowEpochMs(), durationMs = result.durationMs))
                    if (logs.size > 50) logs.removeAt(logs.lastIndex)
                }
                delay(if (result.ok) 1600 else 2000)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (epoch != documentEpoch) return@launch
                kotlinx.coroutines.withContext(
                    kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate,
                ) {
                    if (epoch == documentEpoch) recordInternalFailure(cmd, reason, failure)
                }
                delay(2000)
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
                    val tokens = executionTokens[cmd.id]
                    tokens?.remove(token)
                    if (tokens?.isEmpty() != false) {
                        executionTokens.remove(cmd.id)
                        executing.remove(cmd.id)
                        executionMessages.remove(cmd.id)
                    }
                }
            }
        }
        executionJobs.getOrPut(cmd.id) { mutableListOf() }.add(job)
        job.invokeOnCompletion {
            executionJobs[cmd.id]?.remove(job)
            if (executionJobs[cmd.id]?.isEmpty() == true) executionJobs.remove(cmd.id)
        }
        return job
    }

    /** A condition/support failure is visible in the same list but never counts as an execution. */
    fun recordSkippedRun(cmdId: Long, reason: String, detail: String) {
        val cmd = find(cmdId) ?: return
        val time = now()
        logs.add(0, RunLog(newId(), cmd.title, reason, time, false, cmd.ic, detail,
            emptyList(), skipped = true, at = nowEpochMs()))
        if (logs.size > 50) logs.removeAt(logs.lastIndex)
    }

    /** A trigger that could not start its background host; a real failure, not a condition skip. */
    fun recordTriggerFailure(cmdId: Long, reason: String, detail: String) {
        val cmd = find(cmdId) ?: return
        val time = now()
        update(cmdId) { it.copy(lastRun = time) }
        logs.add(0, RunLog(newId(), cmd.title, reason, time, false, cmd.ic, detail, at = nowEpochMs()))
        if (logs.size > 50) logs.removeAt(logs.lastIndex)
    }

    private fun internalFailure(cmdId: Long, reason: String, failure: Throwable): com.flowglass.demo.system.exec.RunResult {
        val detail = internalErrorDetail(failure)
        return com.flowglass.demo.system.exec.RunResult(
            cmdId, reason,
            listOf(com.flowglass.demo.system.exec.StepResult(
                cmdId, "执行", com.flowglass.demo.system.exec.StepStatus.FAILURE, detail, 0)),
            0,
        )
    }

    private fun recordInternalFailure(cmd: Cmd, reason: String, failure: Throwable) {
        val detail = internalErrorDetail(failure)
        executionMessages[cmd.id] = detail
        executing[cmd.id] = 3
        val time = now()
        update(cmd.id) { it.copy(lastRun = time) }
        logs.add(0, RunLog(newId(), cmd.title, reason, time, false, cmd.ic, detail,
            listOf(com.flowglass.demo.system.exec.StepResult(
                cmd.id, "执行", com.flowglass.demo.system.exec.StepStatus.FAILURE, detail, 0)),
            at = nowEpochMs()))
        if (logs.size > 50) logs.removeAt(logs.lastIndex)
    }
}

private fun internalErrorDetail(failure: Throwable): String {
    val name = failure.javaClass.simpleName.ifBlank { failure.javaClass.name }
    val message = failure.message?.takeIf { it.isNotBlank() } ?: "无消息"
    return "内部错误：$name: $message"
}
