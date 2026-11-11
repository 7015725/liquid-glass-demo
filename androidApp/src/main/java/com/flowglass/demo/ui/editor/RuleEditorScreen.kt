package com.flowglass.demo.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.ui.C
import com.flowglass.demo.ui.T
import com.flowglass.engine.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Authoring only. The parent owns asynchronous persistence and closes after a successful save. */
@Composable
fun RuleEditorScreen(initialRule: Rule, globals: List<Variable>, onSave: (Rule) -> Unit, onCancel: () -> Unit) {
    val model = rememberEditorDraft(initialRule)
    val draft = model.rule
    val inputs = rememberEditorInputs(initialRule.id)
    var tab by rememberSaveable(initialRule.id) { mutableStateOf("basics") }
    var errors by rememberSaveable(initialRule.id) { mutableStateOf("") }
    var discard by rememberSaveable(initialRule.id) { mutableStateOf(false) }
    var confirmManual by rememberSaveable(initialRule.id) { mutableStateOf(false) }
    val tabState = rememberSaveableStateHolder()
    val close = { if (draft != initialRule || inputs.values.isNotEmpty()) discard = true else onCancel() }
    fun manual() {
        inputs.attempt("type") {
            model.update(draft.copy(type = "manual", triggers = emptyList(), conditions = emptyList(), triggerMatch = MatchSpec(), conditionMatch = MatchSpec()))
            inputs.clear("triggerMatch"); inputs.clear("conditionMatch")
            if (tab in setOf("triggers", "conditions")) tab = "actions"
            confirmManual = false
        }
    }
    BackHandler(onBack = close)
    EditorFrame("指令编写器", close, "保存", "rule-save", onAction = {
        val all = draft.triggers + draft.conditions + draft.actions
        val limits = buildList {
            if (componentCount(all) > EDITOR_MAX_COMPONENTS) add("components：整条指令最多 $EDITOR_MAX_COMPONENTS 个组件（含嵌套）")
            if (all.any { componentDepth(it) > EDITOR_MAX_DEPTH }) add("actions：嵌套动作深度不能超过 $EDITOR_MAX_DEPTH")
            fun treeDepth(node: MatchNode): Int = 1 + (node.children.maxOfOrNull { treeDepth(it) } ?: 0)
            listOf("triggerMatch" to draft.triggerMatch, "conditionMatch" to draft.conditionMatch).forEach { (path, spec) ->
                if (spec.tree?.let { treeDepth(it) > EDITOR_MAX_DEPTH } == true) add("$path：逻辑树深度不能超过 $EDITOR_MAX_DEPTH")
            }
        }
        errors = (listOf(inputs.errorText()) + limits + RuleValidator.validate(draft).map { "${it.path}：${it.message}" }).filter { it.isNotBlank() }.joinToString("\n")
        if (errors.isBlank()) {
            try { onSave(draft) } catch (failure: Exception) { errors = failure.message ?: "保存失败，草稿仍保留" }
        }
    }) {
        EditorChoices("指令类型", listOf("auto" to "自动指令", "manual" to "一键指令"), draft.type, "type") { selected ->
            if (selected != draft.type) {
                if (selected == "manual") {
                    if (draft.triggers.isNotEmpty() || draft.conditions.isNotEmpty()) confirmManual = true else manual()
                } else inputs.attempt("type") { model.update(draft.copy(type = "auto")) }
            }
        }
        val tabs = listOf("basics" to "基本信息") +
            (if (draft.type != "manual") listOf("triggers" to "触发器", "conditions" to "条件") else emptyList()) +
            listOf("actions" to "动作", "locals" to "局部变量")
        EditorChoices("编写分区", tabs, tab, "tab") { tab = it }
        EditorErrors(listOf(errors, inputs.errorText()).filter { it.isNotBlank() }.joinToString("\n"))
        tabState.SaveableStateProvider(tab) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (tab) {
                    "basics" -> {
                        EditorCard("基本信息") {
                            EditorField("指令名称", inputs.value("name", draft.name), { text ->
                                inputs.edit("name", text, "text") { model.update(draft.copy(name = it.jsonPrimitive.content)) }
                            }, "rule-name")
                            EditorField("描述", inputs.value("description", draft.description), { text ->
                                inputs.edit("description", text, "text") { model.update(draft.copy(description = it.jsonPrimitive.content)) }
                            }, "rule-description", multiline = true)
                            EditorChoices("重复触发策略", listOf("drop" to "忽略新触发", "queue" to "排队", "restart" to "重新开始", "parallel" to "并行"), draft.runMode, "run-mode") { inputs.attempt("runMode") { model.update(draft.copy(runMode = it)) } }
                            EditorField("防抖毫秒", inputs.value("debounceMillis", draft.debounceMillis.toString()), { text ->
                                inputs.edit("debounceMillis", text, "long") { model.update(draft.copy(debounceMillis = it.jsonPrimitive.long)) }
                            }, "rule-debounce", numeric = true, hint = "非负64位整数；0表示不防抖。")
                            T("这里只编写和保存，不监听事件、不执行任务。${if (draft.type == "manual") "一键指令不包含触发器与条件。" else "自动指令至少需要一个触发器。"}", 13.sp, color = C.OnSurfaceVar)
                        }
                        VariableReferenceHelp(globals, draft.locals, draft.triggers + draft.conditions + draft.actions)
                    }
                    "triggers" -> {
                        MatchEditor(draft.triggerMatch, draft.triggers, "triggerMatch", inputs) { model.update(draft.copy(triggerMatch = it)) }
                        ComponentListEditor("触发器", "triggers", Kind.TRIGGER, draft.triggers, globals, draft.locals) { model.update(draft.copy(triggers = it)) }
                    }
                    "conditions" -> {
                        MatchEditor(draft.conditionMatch, draft.conditions, "conditionMatch", inputs) { model.update(draft.copy(conditionMatch = it)) }
                        ComponentListEditor("条件", "conditions", Kind.CONDITION, draft.conditions, globals, draft.locals) { model.update(draft.copy(conditions = it)) }
                    }
                    "actions" -> ComponentListEditor("动作", "actions", Kind.ACTION, draft.actions, globals, draft.locals) { model.update(draft.copy(actions = it)) }
                    "locals" -> LocalVariablesEditor(draft.locals) { model.update(draft.copy(locals = it)) }
                }
            }
        }
    }
    if (discard) DiscardDialog({ discard = false }, onCancel)
    if (confirmManual) DiscardDialog({ confirmManual = false }, { manual() }, "切换为一键指令将移除触发器、条件及其匹配树；动作与变量保留。继续切换？")
}
