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
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleCodec
import com.flowglass.engine.Variable

/** No optimistic dismissal: observe the exact saved variable in parent-supplied state first. */
@Composable
fun GlobalVariablesScreen(globals: List<Variable>, onSave: (Variable) -> Unit, onDelete: (String) -> Unit, onBack: () -> Unit) {
    var editingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(globals, pendingJson) {
        pendingJson?.let { json ->
            val requested = RuleCodec.decodeRule(json).locals.single()
            if (globals.any { it == requested }) { editingJson = null; adding = false; pendingJson = null }
        }
    }
    BackHandler(onBack = onBack)
    EditorFrame("全局变量", onBack, "新建", "global-add", onAction = { adding = true; editingJson = null; pendingJson = null }) {
        EditorErrors(error)
        EditorField("搜索全局变量", query, { query = it }, "global-search")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("全局变量独立持久化，所有指令可引用。内部事件和动作输出只读，不在这里修改。", 13.sp, color = C.OnSurfaceVar)
            if (globals.isEmpty()) EditorCard { T("还没有全局变量，点击新建。") }
            globals.filter { it.name.contains(query, ignoreCase = true) }.sortedBy { it.name }.forEach { variable ->
                EditorCard(variable.name) {
                    T("global.${variable.name} · ${variable.type}", 13.sp, color = C.OnSurfaceVar)
                    T(variable.initial.toString(), 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorButton("编辑", "global-edit-${variable.name}") { editingJson = RuleCodec.encodeRule(Rule(locals = listOf(variable))); adding = false; pendingJson = null }
                        EditorButton("删除", "global-delete-${variable.name}") { deleting = variable.name }
                    }
                }
            }
        }
    }
    if (adding || editingJson != null) {
        val initial = editingJson?.let { RuleCodec.decodeRule(it).locals.single() }
        VariableEditorDialog(initial, globals.map { it.name }, true, {
            adding = false; editingJson = null; pendingJson = null
        }) { variable ->
            pendingJson = RuleCodec.encodeRule(Rule(locals = listOf(variable)))
            onSave(variable)
        }
    }
    deleting?.let { name ->
        EditorDialog({ deleting = null }) {
            EditorFrame("删除全局变量", { deleting = null }, "确认删除", "global-delete-confirm", onAction = {
                try { onDelete(name); deleting = null } catch (failure: Exception) { error = failure.message ?: "删除失败"; deleting = null }
            }) {
                EditorCard { T("确定删除 global.$name？已有指令的引用不会自动改写，请检查相关指令。") }
            }
        }
    }
}
