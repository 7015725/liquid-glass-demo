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
)

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

    internal fun bindExecutionEngine(engine: com.flowglass.demo.system.exec.ExecutionEngine) {
        executionEngine = engine
        // Async run_rule starts a normal AppState run: its own log row, job, stop button and budget.
        engine.ruleLookup = { target ->
            (autoCmds + oneTaps).firstOrNull { it.title.trim() == target.trim() }
        }
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
        engine.variableWriter = { ruleId, ruleWrites, globalWrites ->
            com.flowglass.demo.system.exec.ExecutionRuntime.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                applyVariableWrites(ruleId, ruleWrites, globalWrites)
            }
        }
    }

    /** Merge-by-key write-back: keys the run never touched keep their stored value. */
    private fun applyVariableWrites(ruleId: String, ruleWrites: Map<String, Any?>, globalWrites: Map<String, Any?>) {
        if (ruleWrites.isNotEmpty()) {
            engineRules = engineRules.map { rule ->
                if (rule.id != ruleId) rule else {
                    var locals = rule.locals
                    for ((name, value) in ruleWrites) {
                        val text = value?.toString().orEmpty()
                        val index = locals.indexOfFirst { it.name == name }
                        locals = if (index >= 0) locals.toMutableList().also {
                            it[index] = it[index].copy(initial = kotlinx.serialization.json.JsonPrimitive(text))
                        } else locals + com.flowglass.engine.LocalVariable(
                            name = name, type = "text", initial = kotlinx.serialization.json.JsonPrimitive(text),
                        )
                    }
                    rule.copy(locals = locals)
                }
            }
        }
        if (globalWrites.isNotEmpty()) {
            engineGlobals = engineGlobals + globalWrites.mapValues {
                kotlinx.serialization.json.JsonPrimitive(it.value?.toString().orEmpty())
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
    var storageError by mutableStateOf<String?>(null)
    /** 编辑页内的即时提示（例如未通过校验时拒绝启用）。 */
    var editorNotice by mutableStateOf<String?>(null)
    internal var engineRules by mutableStateOf(saved?.engineRules.orEmpty())
    internal var engineGlobals by mutableStateOf(saved?.engineGlobals.orEmpty())
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
        editItems(cmdId, section) { it + CommandItem(newId(), option.name, option.defaultParameter, option.ic, option.typeId) }
        if (section == CommandSection.TRIGGERS) update(cmdId) {
            if (it.auto && it.triggers.size == 1) it.copy(category = option.category) else it
        }
    }

    /** 把旧版/未知步骤换成目录里的真实动作，保留它的失败策略。 */
    fun replaceItem(cmdId: Long, section: CommandSection, itemId: Long, option: CommandOption) =
        editItems(cmdId, section) { items ->
            items.map {
                if (it.id == itemId) it.copy(
                    name = option.name,
                    ic = option.ic,
                    parameter = option.defaultParameter,
                    typeId = option.typeId,
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
        if (meta == null || meta.params.isEmpty()) {
            editorNotice = "该组件没有可编辑参数"
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
        val item = CommandItem(newId(), option.name, option.defaultParameter, option.ic, option.typeId)
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

    /** Adds a component without opening the parameter page (blocks and parameterless items). */
    fun addComponentDirect(
        cmdId: Long,
        section: CommandSection,
        option: CommandOption,
        parentId: Long? = null,
        branch: Int? = null,
    ): Long {
        val item = CommandItem(newId(), option.name, option.defaultParameter, option.ic, option.typeId)
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

    private fun ruleIdFor(cmdId: Long): String? =
        engineUi.presentation.entries.firstOrNull { it.value.legacyId == cmdId }?.key

    fun localsOf(cmdId: Long): List<com.flowglass.engine.LocalVariable> =
        engineRules.firstOrNull { it.id == ruleIdFor(cmdId) }?.locals.orEmpty()

    fun setLocal(cmdId: Long, name: String, value: String, type: String = "text") {
        val id = ruleIdFor(cmdId) ?: run {
            editorNotice = "这条指令还没有存档，先返回列表再进来就能编辑变量"
            return
        }
        engineRules = engineRules.map { rule ->
            if (rule.id != id) rule else {
                val index = rule.locals.indexOfFirst { it.name == name }
                val variable = com.flowglass.engine.LocalVariable(
                    name = name, type = type, initial = kotlinx.serialization.json.JsonPrimitive(value),
                )
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
    }

    fun removeGlobal(name: String) {
        engineGlobals = engineGlobals - name
    }

    fun moveItem(cmdId: Long, section: CommandSection, itemId: Long, delta: Int) =
        editItems(cmdId, section) { items -> items.moveItem(itemId, delta) }

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

    /** Deletes an action step and keeps the whole command snapshot for the undo bar. */
    fun removeActionItemWithUndo(cmdId: Long, itemId: Long) {
        val before = find(cmdId) ?: return
        val item = before.actions.findItem(itemId) ?: return
        editActions(cmdId) { items -> items.removeItem(itemId) }
        undo = UndoInfo(before, index = -1, label = item.name, restoreCommand = true)
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
        val moved = before.actions.moveActionTo(sourceId, targetParentId, targetBranch, targetIndex) ?: return false
        if (moved == before.actions) return false
        update(cmdId) { cmd ->
            val updated = cmd.copy(actions = moved)
            if (updated.descriptionEdited) updated else updated.copy(desc = updated.automaticDescription())
        }
        undo = UndoInfo(
            before, index = -1, label = source.name, restoreCommand = true,
            message = "已移动「${source.name}」",
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
    private val executionTokens = mutableMapOf<Long, Any>()
    private val executionJobs = mutableMapOf<Long, kotlinx.coroutines.Job>()
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
        executionJobs.remove(cmdId)?.cancel()
        executing[cmdId] = 0
        executionMessages[cmdId] = "已手动停止"
        logs.add(0, RunLog(newId(), cmd.title, "手动停止", now(), false, cmd.ic, "已手动停止", emptyList(), skipped = true, at = nowEpochMs()))
        if (logs.size > 50) logs.removeAt(logs.lastIndex)
    }

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

    /** Replaces the whole in-memory document after an import/restore has been fully validated. */
    internal fun replaceWith(converted: PersistedAppState) {
        autoCmds.clear(); autoCmds.addAll(converted.autoCmds)
        oneTaps.clear(); oneTaps.addAll(converted.oneTaps)
        logs.clear(); logs.addAll(converted.logs)
        engineRules = converted.engineRules
        engineUi = converted.engineUi
        engineGlobals = converted.engineGlobals
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
        return copy.id
    }

    fun delete(id: Long) {
        // Normalize the edited title before capturing the undo snapshot.
        if (editing == id) closeEditor()
        val src = find(id) ?: return
        val l = listFor(src.auto)
        val i = l.indexOf(src)
        l.removeAt(i)
        undo = UndoInfo(src, i)
    }

    fun undoDelete() {
        val u = undo ?: return
        if (u.restoreCommand) {
            update(u.cmd.id) { u.cmd }
        } else {
            val l = listFor(u.cmd.auto)
            l.add(u.index.coerceIn(0, l.size), u.cmd)
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
    ): kotlinx.coroutines.Job? {
        if (executing.containsKey(cmd.id)) return null
        val engine = executionEngine
        val token = Any()
        executionTokens[cmd.id] = token
        executionMessages.remove(cmd.id)
        executing[cmd.id] = 1
        val job = com.flowglass.demo.system.exec.ExecutionRuntime.scope.launch {
            try {
                val result = try {
                    engine?.run(cmd, reason, variables) ?: com.flowglass.demo.system.exec.RunResult(
                        cmd.id, reason, listOf(com.flowglass.demo.system.exec.StepResult(
                            cmd.id, "执行环境检查", com.flowglass.demo.system.exec.StepStatus.FAILURE,
                            "执行环境未就绪", 0)), 0)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    internalFailure(cmd.id, reason, failure)
                }
                if (result.ignored) return@launch
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
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
                kotlinx.coroutines.withContext(
                    kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate,
                ) {
                    recordInternalFailure(cmd, reason, failure)
                }
                delay(2000)
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
                    if (executionTokens[cmd.id] === token) {
                        executionTokens.remove(cmd.id)
                        executing.remove(cmd.id)
                        executionMessages.remove(cmd.id)
                    }
                }
            }
        }
        executionJobs[cmd.id] = job
        job.invokeOnCompletion { if (executionJobs[cmd.id] === job) executionJobs.remove(cmd.id) }
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
