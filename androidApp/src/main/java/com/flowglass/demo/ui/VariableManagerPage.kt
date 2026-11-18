package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
    var creating by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().testTag("variable-manager").padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T("变量", 24.sp, FontWeight.Bold)
                T("内置变量由 App 提供，只读；局部变量属于一条指令；全局变量所有指令共用。插入时统一写 {名字}。",
                    12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
            }
            EditorPill("新建变量", "新建变量") { creating = true }
        }
        VariableScope.values().forEach { scope ->
            val rows = options.filter { it.scope == scope }
            if (rows.isNotEmpty()) {
                T(VariableCatalog.scopeLabel(scope), 13.sp, FontWeight.SemiBold, color = C.Muted)
                rows.forEach { option ->
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
    if (creating) {
        NewVariableDialog(state, onDismiss = { creating = false })
    }
}

@Composable
private fun NewVariableDialog(state: AppState, onDismiss: () -> Unit) {
    var scope by remember { mutableStateOf(VariableScope.LOCAL) }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(com.flowglass.engine.VariableType.TEXT) }
    var commandIndex by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val commands = state.allCommands()
    val target = commands.getOrNull(commandIndex)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedRectangle(24.dp))
                    .background(C.Card).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                T("新建变量", 18.sp, FontWeight.SemiBold)
                T("保存范围", 12.sp, color = C.OnSurfaceVar)
                FilterChips(
                    listOf("本指令", "所有指令"),
                    if (scope == VariableScope.LOCAL) 0 else 1,
                    onSelect = { scope = if (it == 0) VariableScope.LOCAL else VariableScope.GLOBAL },
                )
                if (scope == VariableScope.LOCAL) {
                    T("所属指令", 12.sp, color = C.OnSurfaceVar)
                    if (commands.isEmpty()) {
                        T("还没有指令，请先创建一条指令。", 12.sp, color = C.Bad)
                    } else {
                        FilterChips(
                            commands.map { it.title.ifBlank { "未命名指令" } },
                            commandIndex.coerceIn(0, commands.lastIndex),
                            onSelect = { commandIndex = it },
                        )
                    }
                }
                T("变量名", 12.sp, color = C.OnSurfaceVar)
                ParameterTextField(name, "new-variable-name") { name = it }
                T("备注", 12.sp, color = C.OnSurfaceVar)
                ParameterTextField(note, "new-variable-note") { note = it }
                T("类型", 12.sp, color = C.OnSurfaceVar)
                VariableTypePicker(type, onSelect = { type = it })
                T("初始值", 12.sp, color = C.OnSurfaceVar)
                ParameterTextField(value, "new-variable-value") { value = it }
                error?.let { T(it, 12.sp, color = C.Bad) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorPill("创建", "创建变量") {
                        val parseError = runCatching {
                            com.flowglass.engine.VariableValues.parse(value, type)
                        }.exceptionOrNull()?.message
                        val invalid = VariableCatalog.validateName(
                            state, name.trim(), target?.id, scope,
                            ignoreOwner = if (scope == VariableScope.LOCAL) target?.id else null,
                        ) ?: if (scope == VariableScope.LOCAL && target == null) "请先创建一条指令"
                        else parseError
                        if (invalid != null) {
                            error = invalid
                        } else {
                            state.createVariable(
                                scope = scope,
                                commandId = if (scope == VariableScope.LOCAL) target?.id else null,
                                name = name.trim(),
                                type = type.storageKey,
                                note = note,
                                value = value,
                            )
                            onDismiss()
                        }
                    }
                    EditorPill("取消", "取消新建变量", action = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun VariableDetailDialog(state: AppState, option: VariableOption, onDismiss: () -> Unit) {
    var name by remember(option) { mutableStateOf(option.name) }
    var note by remember(option) { mutableStateOf(option.note) }
    var value by remember(option) { mutableStateOf(option.value) }
    var type by remember(option) { mutableStateOf(option.type) }
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
                    T("类型", 12.sp, color = C.OnSurfaceVar)
                    VariableTypePicker(type, onSelect = { type = it }, tagPrefix = "variable-detail-type")
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
                                val parseError = runCatching {
                                    com.flowglass.engine.VariableValues.parse(value, type)
                                }.exceptionOrNull()?.message
                                if (invalid != null) {
                                    error = invalid
                                } else if (parseError != null) {
                                    error = parseError
                                } else {
                                    if (nextName != option.name) {
                                        state.renameVariable(option.ownerId, option.name, nextName, option.scope)
                                    }
                                    state.setVariableType(option.ownerId, nextName, type, option.scope)
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
