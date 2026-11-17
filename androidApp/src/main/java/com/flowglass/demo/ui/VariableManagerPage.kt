package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.engine.VariableScope
import com.kyant.shapes.RoundedRectangle

/** Read-only view of the built-in catalogue, plus editable local/global variables. */
@Composable
fun VariableManagerPage(state: AppState) {
    val options = VariableCatalog.forManager(state)
    var editing by remember { mutableStateOf<VariableOption?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().testTag("variable-manager"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            T("变量", 24.sp, FontWeight.Bold)
            T("内置变量由 App 提供，只读；局部变量属于一条指令；全局变量所有指令共用。插入时统一写 {名字}。",
                12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
        }
        VariableScope.values().forEach { scope ->
            val rows = options.filter { it.scope == scope }
            if (rows.isNotEmpty()) {
                item(key = "group-$scope") {
                    T(VariableCatalog.scopeLabel(scope), 13.sp, FontWeight.SemiBold, color = C.Muted)
                }
                items(rows, key = { "${it.scope}-${it.ownerId}-${it.name}" }) { option ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedRectangle(20.dp)).background(C.Card)
                            .pressable { editing = option }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                T("{${option.name}}", 15.sp, FontWeight.SemiBold)
                                T(VariableCatalog.typeLabel(option.type), 11.sp, color = C.Muted)
                            }
                            if (option.ownerTitle.isNotBlank()) {
                                T("来自：${option.ownerTitle}", 11.sp, color = C.Muted)
                            }
                            T(
                                option.note.ifBlank { if (scope == VariableScope.BUILTIN) "无说明" else "未填写备注" },
                                12.sp, color = C.OnSurfaceVar, lineHeight = 17.sp,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            T(option.value.take(24), 11.sp, color = C.Muted, maxLines = 1)
                            T("引用 ${option.references} 次", 11.sp, color = if (option.references > 0) C.Primary else C.Muted)
                        }
                    }
                }
            }
        }
    }

    editing?.let { option ->
        VariableDetailDialog(state, option, onDismiss = { editing = null })
    }
}

@Composable
private fun VariableDetailDialog(state: AppState, option: VariableOption, onDismiss: () -> Unit) {
    var name by remember(option) { mutableStateOf(option.name) }
    var note by remember(option) { mutableStateOf(option.note) }
    var value by remember(option) { mutableStateOf(option.value) }
    var error by remember(option) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(option) { mutableStateOf(false) }
    val editable = option.scope != VariableScope.BUILTIN

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedRectangle(24.dp))
                    .background(C.Card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                T(if (editable) "编辑变量" else "内置变量", 18.sp, FontWeight.SemiBold)
                if (editable) {
                    T("变量名", 12.sp, color = C.OnSurfaceVar)
                    ParameterTextField(name, "variable-name") { name = it }
                } else {
                    T("{${option.name}}", 15.sp, FontWeight.SemiBold)
                }
                T("备注", 12.sp, color = C.OnSurfaceVar)
                if (editable) {
                    ParameterTextField(note, "variable-note") { note = it }
                } else {
                    T(option.note, 13.sp, color = C.OnSurfaceVar)
                }
                if (option.scope == VariableScope.GLOBAL || option.scope == VariableScope.LOCAL) {
                    T("当前值 / 初始值", 12.sp, color = C.OnSurfaceVar)
                    ParameterTextField(value, "variable-value") { value = it }
                }
                T(
                    "被引用 ${option.references} 次" +
                        if (option.references > 0) "；改名会自动更新引用" else "",
                    12.sp, color = if (option.references > 0) C.Primary else C.Muted,
                )
                error?.let { T(it, 12.sp, color = C.Bad) }
                if (confirmDelete) {
                    T("删除后，引用会保留原名字但不再解析。确定删除？", 12.sp, color = C.Bad)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorPill("确定删除", "确认删除变量") {
                            state.deleteVariable(option.ownerId, option.name, option.scope)
                            onDismiss()
                        }
                        EditorPill("取消", "取消删除变量") { confirmDelete = false }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editable) {
                            EditorPill("保存", "保存变量") {
                                val nextName = name.trim()
                                val invalid = com.flowglass.engine.VariableName.error(nextName)
                                    ?: if (nextName == option.name) null
                                    else VariableCatalog.validateName(
                                        state, nextName, option.ownerId, option.scope,
                                        ignoreOwner = if (option.scope == VariableScope.LOCAL) option.ownerId else null,
                                        ignoreName = option.name,
                                    )
                                if (invalid != null) {
                                    error = invalid
                                } else {
                                    if (nextName != option.name) {
                                        state.renameVariable(option.ownerId, option.name, nextName, option.scope)
                                    }
                                    state.setVariableNote(option.ownerId, nextName, note, option.scope)
                                    state.setVariableValue(option.ownerId, nextName, value, option.scope)
                                    onDismiss()
                                }
                            }
                            EditorPill("删除", "删除变量") { confirmDelete = true }
                        }
                        EditorPill("关闭", "关闭变量详情", action = onDismiss)
                    }
                }
            }
        }
    }
}
