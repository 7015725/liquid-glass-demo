package com.flowglass.demo.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.ui.C
import com.flowglass.demo.ui.T
import com.flowglass.engine.*
import kotlinx.serialization.json.*

@Composable
internal fun ComponentListEditor(
    label: String, path: String, kind: Kind, components: List<Component>,
    globals: List<Variable>, locals: List<Variable>, depth: Int = 1, onChange: (List<Component>) -> Unit
) {
    var picking by rememberSaveable(path) { mutableStateOf(false) }
    var editingJson by rememberSaveable(path) { mutableStateOf<String?>(null) }
    var isNew by rememberSaveable(path) { mutableStateOf(false) }
    var notice by rememberSaveable(path) { mutableStateOf("") }
    fun attempt(change: () -> Unit) {
        try { change(); notice = "" }
        catch (failure: IllegalArgumentException) { notice = failure.message ?: "草稿超过可保存上限" }
    }
    T("$label · ${components.size}", 17.sp)
    components.forEachIndexed { index, component ->
        key(component.id) {
            EditorCard("${index + 1}. ${ComponentRegistry.find(component.type)?.name ?: component.type}") {
                T(component.params.entries.joinToString(" · ") { "${it.key}: ${it.value}" }.ifBlank { "无需参数" }, 12.sp, color = C.OnSurfaceVar)
                if (component.children.isNotEmpty() || component.elseActions.isNotEmpty())
                    T("内部动作 ${componentCount(component.children)} · 否则 ${componentCount(component.elseActions)}", 12.sp)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorButton("编辑", "edit-${component.id}") {
                        attempt { isNew = false; editingJson = EditorDraft.encode(Rule(actions = listOf(component))) }
                    }
                    if (index > 0) EditorButton("上移", "up-${component.id}") {
                        attempt { onChange(components.toMutableList().apply { add(index - 1, removeAt(index)) }) }
                    }
                    if (index < components.lastIndex) EditorButton("下移", "down-${component.id}") {
                        attempt { onChange(components.toMutableList().apply { add(index + 1, removeAt(index)) }) }
                    }
                    EditorButton("复制", "copy-${component.id}") {
                        if (componentCount(components) + componentCount(listOf(component)) > EDITOR_MAX_COMPONENTS) notice = "当前列表最多 $EDITOR_MAX_COMPONENTS 个组件（含嵌套）"
                        else attempt { onChange(components.toMutableList().apply { add(index + 1, copyEditorComponent(component)) }) }
                    }
                    EditorButton("删除", "delete-${component.id}") { attempt { onChange(components.filterNot { it.id == component.id }) } }
                }
            }
        }
    }
    if (depth <= EDITOR_MAX_DEPTH && componentCount(components) < EDITOR_MAX_COMPONENTS) {
        EditorButton("添加$label", "add-$path", selected = true) { picking = true }
    } else T("已达编写上限：深度 $EDITOR_MAX_DEPTH，列表组件 $EDITOR_MAX_COMPONENTS。", 13.sp, color = C.Bad)
    EditorErrors(notice, "$path-notice")
    if (picking) ComponentPicker(kind, onDismiss = { picking = false }) { component ->
        isNew = true; editingJson = EditorDraft.encode(Rule(actions = listOf(component))); picking = false
    }
    editingJson?.let { json ->
        val original = remember(json) { RuleCodec.decodeRule(json) }
        ComponentEditorDialog(original, kind, depth, globals, locals, onDismiss = { editingJson = null }) { edited ->
            onChange(if (isNew) components + edited else components.map { if (it.id == edited.id) edited else it })
            editingJson = null
        }
    }
}

@Composable
private fun ComponentPicker(kind: Kind, onDismiss: () -> Unit, onPick: (Component) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    val items = ComponentRegistry.items.filter { it.kind == kind }
    EditorDialog(onDismiss) {
        EditorFrame("选择组件", onDismiss) {
            EditorField("搜索名称、说明或 typeId", query, { query = it }, "component-search")
            EditorChoices("分类", (listOf("全部") + items.map { it.category }.distinct()).map { it to it }, category, "category") { category = it }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val filtered = items.filter { (category == "全部" || it.category == category) && (it.name + it.typeId + it.description).contains(query, ignoreCase = true) }
                if (filtered.isEmpty()) T("没有符合搜索条件的组件")
                filtered.forEach { meta ->
                    EditorCard(meta.name) {
                        T(meta.description, 12.sp, color = C.OnSurfaceVar)
                        T("${meta.category} · ${meta.typeId} · ${meta.minTier}", 12.sp, color = C.OnSurfaceVar)
                        if (meta.permissions.isNotEmpty()) T("所需权限：${meta.permissions.joinToString()}（不代表已授权）", 12.sp)
                        EditorButton("选择${meta.name}", "pick-${meta.typeId}") { onPick(ComponentRegistry.newComponent(meta.typeId)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentEditorDialog(initialHolder: Rule, kind: Kind, depth: Int, globals: List<Variable>, locals: List<Variable>,
                                  onDismiss: () -> Unit, onDone: (Component) -> Unit) {
    // Reuse the preflighted holder, including its per-opening saveable identity.
    // Copying the component ID into Rule.id would change the encoded budget.
    val holder = rememberEditorDraft(initialHolder)
    val initial = initialHolder.actions.single()
    val component = holder.rule.actions.single()
    fun update(c: Component) { holder.update(holder.rule.copy(actions = listOf(c))) }
    val inputs = rememberEditorInputs(initial.id)
    var errors by rememberSaveable(initial.id) { mutableStateOf("") }
    var discard by rememberSaveable(initial.id) { mutableStateOf(false) }
    val close = { if (component != initial || inputs.values.isNotEmpty()) discard = true else onDismiss() }
    val meta = ComponentRegistry.find(component.type)
    EditorDialog(close) {
        BackHandler(onBack = close)
        EditorFrame(meta?.name ?: component.type, close, "完成", "component-done", onAction = {
            val candidate = Rule(name = "组件表单", type = if (kind == Kind.ACTION) "manual" else "auto", locals = locals,
                triggers = if (kind == Kind.TRIGGER) listOf(component) else if (kind == Kind.CONDITION) listOf(ComponentRegistry.newComponent("trigger.screen")) else emptyList(),
                conditions = if (kind == Kind.CONDITION) listOf(component) else emptyList(),
                actions = if (kind == Kind.ACTION) listOf(component) else listOf(ComponentRegistry.newComponent("action.stop")))
            errors = (listOf(inputs.errorText()) + RuleValidator.validate(candidate).map { "${it.path}：${it.message}" }).filter { it.isNotBlank() }.joinToString("\n")
            if (errors.isBlank()) {
                // A valid component can still exceed its enclosing holder/rule's budget.
                // Let the rejection return here before the list closes this dialog.
                try { onDone(component) }
                catch (failure: IllegalArgumentException) { errors = failure.message ?: "草稿超过可保存上限，请修改输入" }
            }
        }) {
            EditorErrors(errors, "component-errors")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorCard("参数") {
                    if (meta == null) T("未知组件，原始数据保留。不能保存为有效指令。", color = C.Bad)
                    meta?.let {
                        T(it.description, 13.sp, color = C.OnSurfaceVar)
                        if (it.permissions.isNotEmpty()) T("权限声明：${it.permissions.joinToString()}\n仅编写，不申请权限、不执行。", 12.sp)
                        it.params.forEach { spec ->
                            val current = component.params[spec.key] ?: spec.defaultValue
                            val fieldKey = "params.${spec.key}"
                            val raw = inputs.value(fieldKey, editorValueText(current, spec.kind))
                            val setValue: (String) -> Unit = { text ->
                                inputs.edit(fieldKey, text, spec.kind) { parsed -> update(component.copy(params = component.params + (spec.key to parsed))) }
                            }
                            when (spec.kind) {
                                "bool" -> EditorChoices(spec.label, listOf("true" to "是", "false" to "否"), raw, "param-${spec.key}", setValue)
                                "choice" -> EditorChoices(spec.label, spec.choices.map { value -> value to value }, raw, "param-${spec.key}", setValue)
                                else -> EditorField(spec.label + if (spec.required) " *" else "（可选）", raw, setValue, "param-${spec.key}",
                                    multiline = spec.kind in setOf("json", "code"), numeric = spec.kind in setOf("long", "double"), hint = "${spec.kind} ${spec.hint}")
                            }
                        }
                        if (it.params.isEmpty()) T("此组件无需参数。", 13.sp)
                    }
                    EditorErrors(inputs.errorText(), "parameter-errors")
                }
                if (kind == Kind.ACTION) EditorCard("超时与失败处理") {
                    EditorField("超时秒数", inputs.value("timeoutSeconds", component.timeoutSeconds.toString()), { text ->
                        inputs.edit("timeoutSeconds", text, "int32") { update(component.copy(timeoutSeconds = it.jsonPrimitive.int)) }
                    }, "component-timeout", numeric = true, hint = "1 到 2147483647 秒")
                    EditorChoices("失败策略", listOf("stop" to "停止", "continue" to "继续", "retry" to "重试"), component.onError, "error-policy") { inputs.attempt("onError") { update(component.copy(onError = it)) } }
                    EditorField("重试次数", inputs.value("retryCount", component.retryCount.toString()), { text ->
                        inputs.edit("retryCount", text, "int32") { update(component.copy(retryCount = it.jsonPrimitive.int)) }
                    }, "component-retries", numeric = true)
                }
                if (component.type in setOf("action.if", "action.loop", "action.list_foreach")) {
                    if (depth < EDITOR_MAX_DEPTH) {
                        EditorCard(if (component.type == "action.if") "满足条件 · THEN" else "循环体") {
                            ComponentListEditor("内部动作", "${component.id}-children", Kind.ACTION, component.children, globals, locals, depth + 1) { update(component.copy(children = it)) }
                        }
                        if (component.type == "action.if") EditorCard("不满足 · ELSE") {
                            ComponentListEditor("否则动作", "${component.id}-else", Kind.ACTION, component.elseActions, globals, locals, depth + 1) { update(component.copy(elseActions = it)) }
                        }
                    } else T("嵌套深度上限 $EDITOR_MAX_DEPTH；不能继续添加内部动作。", color = C.Bad)
                }
                VariableReferenceHelp(globals, locals, listOf(component))
            }
        }
        if (discard) DiscardDialog({ discard = false }, onDismiss)
    }
}

@Composable
internal fun VariableReferenceHelp(globals: List<Variable>, locals: List<Variable>, components: List<Component>) {
    var open by rememberSaveable { mutableStateOf(false) }
    EditorButton(if (open) "收起变量引用帮助" else "变量引用帮助", "variable-help") { open = !open }
    if (open) EditorCard("变量与内部输出") {
        T("变量字段填写 local.名称 / global.名称；JSON 参数中的引用需用字符串。仅编写，引用能否执行由未来执行端决定。", 13.sp)
        T("局部：${locals.joinToString { "local.${it.name} (${it.type})" }.ifBlank { "暂无" }}", 13.sp)
        T("全局：${globals.joinToString { "global.${it.name} (${it.type})" }.ifBlank { "暂无" }}", 13.sp)
        fun outputs(list: List<Component>): List<String> = list.flatMap { (ComponentRegistry.find(it.type)?.outputs ?: emptyList()) + outputs(it.children) + outputs(it.elseActions) }
        T("内部只读：${outputs(components).distinct().joinToString().ifBlank { "当前组件无声明输出" }}\n不能覆盖 event / 动作输出。", 13.sp)
    }
}
