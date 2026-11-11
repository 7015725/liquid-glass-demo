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

internal val VariableTypeChoices = listOf("string" to "字符串", "int" to "整数", "decimal" to "小数", "bool" to "布尔", "list" to "列表", "dict" to "字典", "null" to "空值")

@Composable
internal fun VariableEditorDialog(initial: Variable?, existingNames: List<String>, global: Boolean,
                                   onDismiss: () -> Unit, onSave: (Variable) -> Unit) {
    val original = initial ?: Variable("")
    var name by rememberSaveable { mutableStateOf(original.name) }
    var type by rememberSaveable { mutableStateOf(original.type) }
    var value by rememberSaveable { mutableStateOf(editorValueText(original.initial, original.type)) }
    var reset by rememberSaveable { mutableStateOf(original.resetEachRun) }
    var errors by rememberSaveable { mutableStateOf("") }
    var discard by rememberSaveable { mutableStateOf(false) }
    val dirty = name != original.name || type != original.type || value != editorValueText(original.initial, original.type) || reset != original.resetEachRun
    val close = { if (dirty) discard = true else onDismiss() }
    EditorDialog(close) {
        BackHandler(onBack = close)
        EditorFrame(if (global) "全局变量" else "局部变量", close, "保存", "variable-save", onAction = {
            val parsed = parseEditorValue(type, value)
            val variable = parsed?.let { Variable(name, type, it, if (global) false else reset) }
            errors = when {
                name != initial?.name && name in existingNames -> "name：变量重名，请使用其他名称"
                parsed == null -> "initial：请输入合法的 $type 初始值；整数必须是64位整数，JSON不可缺少引号或括号"
                else -> RuleValidator.validateVariable(requireNotNull(variable)).joinToString("\n") { "${it.path}：${it.message}" }
            }
            if (errors.isBlank() && variable != null) {
                try { onSave(variable) } catch (failure: Exception) { errors = failure.message ?: "保存失败，草稿仍保留" }
            }
        }) {
            EditorErrors(errors, "variable-errors")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorCard("定义") {
                    EditorField("变量名称", name, { name = it }, "variable-name", readOnly = global && initial != null,
                        hint = if (global && initial != null) "全局变量名不可原地修改，以免破坏引用；可新建后删除旧变量。" else "不带 global. / local. 前缀；不能以数字开头")
                    EditorChoices("变量类型", VariableTypeChoices, type, "variable-type") { type = it }
                    EditorField("初始值", value, { value = it }, "variable-initial", multiline = type in setOf("list", "dict"),
                        hint = when (type) {
                            "string" -> "直接输入文本，无需 JSON 引号。"
                            "int" -> "64位整数，不支持小数或科学计数法。"
                            "decimal" -> "有限小数，不能使用 NaN 或 Infinity。"
                            "bool" -> "true 或 false"
                            "list" -> "JSON 数组，例如 [1, \"文本\"]"
                            "dict" -> "JSON 对象，例如 {\"key\": 1}"
                            else -> "填写 null"
                        })
                    if (!global) EditorChoices("每次运行重置 resetEachRun", listOf("true" to "每次重置", "false" to "保留值"), reset.toString(), "variable-reset") { reset = it == "true" }
                    T(if (global) "保存后交由父级持久化；数据库确认前不关闭此表单。" else "局部变量随整条指令保存；这里不会执行任务。", 12.sp, color = C.OnSurfaceVar)
                }
            }
        }
        if (discard) DiscardDialog({ discard = false }, onDismiss)
    }
}

@Composable
internal fun LocalVariablesEditor(locals: List<Variable>, onChange: (List<Variable>) -> Unit) {
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    T("局部变量 · ${locals.size}", 17.sp)
    locals.forEachIndexed { index, v ->
        EditorCard(v.name) {
            T("${v.type} · ${v.initial} · ${if (v.resetEachRun) "每次重置" else "保留值"}", 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorButton("编辑", "local-edit-$index") { editingIndex = index }
                EditorButton("删除", "local-delete-$index") { onChange(locals.filterIndexed { i, _ -> i != index }) }
            }
        }
    }
    if (locals.size < 100) EditorButton("添加局部变量", "local-add") { editingIndex = -1 }
    editingIndex?.let { index ->
        VariableEditorDialog(locals.getOrNull(index), locals.map { it.name }, false, { editingIndex = null }) { variable ->
            onChange(if (index == -1) locals + variable else locals.mapIndexed { i, old -> if (i == index) variable else old })
            editingIndex = null
        }
    }
}
