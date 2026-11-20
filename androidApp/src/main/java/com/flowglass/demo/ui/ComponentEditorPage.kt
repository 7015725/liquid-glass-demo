package com.flowglass.demo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.flowglass.demo.xposed.ModernModuleStatus
import com.flowglass.demo.xposed.ModuleStatus
import com.flowglass.demo.xposed.method.MethodHookStatusRegistry
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.methodhook.AppMethodCall
import com.flowglass.engine.methodhook.MethodHookStatus
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.launch
import com.kyant.shapes.RoundedRectangle

/**
 * One parameter page for triggers, conditions and actions. It edits [AppState.componentDraft]
 * only; [AppState.saveComponentDraft] is the single place that mutates the command.
 */
@Composable
internal fun ComponentEditorHost(
    state: AppState,
    onAuthorization: (Int) -> Unit = {},
) {
    val draft = state.componentDraft
    val legacyCmd = state.actionEditingCmd
    val legacyItem = state.actionEditing
    LaunchedEffect(draft, legacyCmd, legacyItem) {
        // Navigation snapshots only store ids. Rebuild the draft after process restore so an
        // already-open action page does not turn into a blank screen.
        if (draft == null && legacyCmd != null && legacyItem != null) {
            val item = state.itemById(legacyCmd, legacyItem)
            if (item != null) state.openComponentEditor(legacyCmd, CommandSection.ACTIONS, item)
        }
    }
    var last by remember { mutableStateOf<ComponentDraft?>(null) }
    if (draft != null) last = draft
    AnimatedVisibility(
        draft != null,
        enter = fadeIn(effect()) + scaleIn(spatial(), initialScale = 0.96f),
        exit = fadeOut(effect()) + scaleOut(spatial(), targetScale = 0.96f),
    ) {
        val shown = draft ?: last
        if (shown != null) key(shown.item.id, shown.isNew) {
            ComponentEditorPage(state, shown, onAuthorization)
        }
    }
}

/** Compatibility entry point used by existing hosts and tests. */
@Composable
internal fun ActionEditorHost(state: AppState) = ComponentEditorHost(state)

@Composable
private fun ComponentEditorPage(
    state: AppState,
    draft: ComponentDraft,
    onAuthorization: (Int) -> Unit,
) {
    val cmd = state.find(draft.cmdId) ?: return
    val item = draft.item
    var discardOpen by remember { mutableStateOf(false) }
    var moveOpen by remember(item.id) { mutableStateOf(false) }
    val meta = item.typeId?.let { BuiltInComponents.registry().find(it) }
    val hasOutputs = meta != null && meta.kind != com.flowglass.engine.ComponentKind.CONDITION &&
        com.flowglass.engine.OutputCatalog.allOutputs(meta).isNotEmpty()
    var outputPage by remember(item.id) { mutableStateOf(false) }

    fun save() {
        if (state.saveComponentDraft()) discardOpen = false
    }

    fun requestClose() {
        if (draft.dirty) discardOpen = true else state.cancelComponentDraft()
    }

    BackHandler(enabled = true) {
        when {
            moveOpen -> moveOpen = false
            outputPage -> outputPage = false
            else -> requestClose()
        }
    }

    if (moveOpen) {
        Column(
            Modifier.fillMaxSize().background(C.Bg).testTag("action-editor-move")
                .statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DetailTopBar(
                onBack = { moveOpen = false },
                title = { T("移动到…", 20.sp, FontWeight.Bold, maxLines = 1) },
                subtitle = "选择动作要放入的位置",
            )
            MoveToPanel(state, cmd, item.id) { moveOpen = false }
        }
        return
    }

    if (outputPage && hasOutputs) {
        ComponentOutputPage(
            item = item,
            section = draft.section,
            cmdTitle = cmd.title,
            onBack = { outputPage = false },
            onSave = { outputs ->
                state.updateComponentOutputs(outputs)
                outputPage = false
            },
            onConfigureExtractions = { outputPage = false },
            validateName = { name ->
                VariableCatalog.validateName(
                    state, name, draft.cmdId, com.flowglass.engine.VariableScope.OUTPUT,
                    ignoreItem = item.id,
                )
            },
        )
        return
    }

    Column(
        Modifier.fillMaxSize().background(C.Bg).testTag("action-editor")
            .statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DetailTopBar(
            onBack = { requestClose() },
            title = { T(item.name, 20.sp, FontWeight.Bold, maxLines = 1) },
            subtitle = "${parameterSectionLabel(draft.section)}参数 · ${cmd.title}",
            trailing = {
                TopBarTextButton("完成", onClick = ::save)
            },
        )
        ActionParameterEditor(
            section = draft.section,
            item = item,
            onParameter = { value -> state.updateComponentDraft(item.copy(parameter = value)) },
            onDone = ::save,
            commandTitle = cmd.title,
            onAuthorization = { destination ->
                state.cancelComponentDraft()
                onAuthorization(destination)
            },
            targets = (state.autoCmds + state.oneTaps).map { it.title },
            variables = parameterVariables(state, cmd, draft),
            showDone = false,
            canConfigureOutputs = hasOutputs,
            onConfigureOutputs = { outputPage = true },
        )
        if (draft.section == CommandSection.ACTIONS && !draft.isNew) {
            EditorPill("移动到…", "移动到其他位置") { moveOpen = true }
        }
        if (item.typeId == AppMethodCall.TYPE_ID) AppMethodCallStatus(cmd, item)
    }

    if (discardOpen) {
        DiscardDraftDialog(
            title = if (draft.isNew) "放弃添加？" else "放弃修改？",
            message = if (draft.isNew) "还没有保存，返回后不会添加该组件。" else "返回后本次修改不会保留。",
            onKeep = { discardOpen = false },
            onDiscard = {
                discardOpen = false
                state.cancelComponentDraft()
            },
        )
    }
}

@Composable
private fun AppMethodCallStatus(cmd: Cmd, item: CommandItem) {
    val meta = BuiltInComponents.registry().find(AppMethodCall.TYPE_ID) ?: return
    val fields = remember(item.parameter) { ParameterForm.fields(meta, item.parameter) }
    val rawTarget = fields[AppMethodCall.KEY_PACKAGE].orEmpty().trim()
    val target = AppMethodCall.targetKey(rawTarget)
    val hookId = AppMethodCall.hookId(cmd.id, item.id)
    val composeScope = rememberCoroutineScope()
    var scopeApproved by remember(rawTarget) { mutableStateOf(false) }
    var message by remember(rawTarget) { mutableStateOf<String?>(null) }
    LaunchedEffect(rawTarget) {
        scopeApproved = target != null && ModuleStatus.scope().contains(target.first)
    }
    val active = ModuleStatus.isActive()
    val modern = ModuleStatus.isModern()
    val inScope = when {
        target == null -> false
        scopeApproved || ModuleStatus.scope().contains(target.first) -> true
        else -> false
    }
    val installed = MethodHookStatusRegistry.snapshot()
        .filter { it.hookId == hookId && target?.first == it.packageName }
        .maxByOrNull { it.updatedAt }
    Column(
        Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Tonal).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        T("状态", 14.sp, FontWeight.SemiBold)
        T("LSPosed：${if (active) "已激活" else "未激活"}", 13.sp,
            color = if (active) C.GoodText else C.Bad)
        T(
            "目标作用域：" + when {
                target == null -> "先选择目标应用"
                inScope -> "已包含"
                modern -> "未包含"
                else -> "旧版接口无法读取，请手动确认"
            },
            13.sp, color = if (inScope) C.GoodText else C.OnSurfaceVar,
        )
        T(
            "方法：" + when {
                installed == null -> "等待目标应用启动后回报"
                installed.status == MethodHookStatus.INSTALLED ->
                    "已挂上${installed.detail?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}"
                installed.status == MethodHookStatus.NOT_FOUND -> "未找到方法"
                installed.status == MethodHookStatus.AMBIGUOUS -> "有多个重载，请补充参数类型"
                else -> "挂载失败${installed.detail?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}"
            },
            13.sp, color = if (installed?.status == MethodHookStatus.INSTALLED) C.GoodText else C.OnSurfaceVar,
        )
        message?.let { T(it, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp) }
        if (target != null && !inScope) {
            val pkg = target.first
            if (modern) {
                EditorPill("请求加入作用域", "请求加入作用域") {
                    val service = ModernModuleStatus.service()
                    if (service == null) {
                        message = "请在 LSPosed 里为本模块勾选『$pkg』，然后重启该应用"
                    } else {
                        runCatching {
                            service.requestScope(listOf(pkg), object : XposedService.OnScopeEventListener {
                                override fun onScopeRequestApproved(scope: List<String>) {
                                    composeScope.launch {
                                        scopeApproved = true
                                        message = "已加入作用域：$pkg"
                                    }
                                }

                                override fun onScopeRequestFailed(reason: String) {
                                    composeScope.launch {
                                        message = "加入作用域失败：$reason；请在 LSPosed 里手动勾选『$pkg』后重启该应用"
                                    }
                                }
                            })
                        }.onFailure {
                            message = "请在 LSPosed 里为本模块勾选『$pkg』，然后重启该应用"
                        }
                    }
                }
            } else {
                T("请在 LSPosed 里为本模块勾选『$pkg』，然后重启该应用", 12.sp,
                    color = C.OnSurfaceVar, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun DiscardDraftDialog(
    title: String,
    message: String,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    Dialog(onDismissRequest = onKeep, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(C.ScrimSubtle).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedRectangle(28.dp)).background(C.Card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                T(title, 18.sp, FontWeight.Bold)
                T(message, 13.sp, color = C.OnSurfaceVar, lineHeight = 19.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditorPill("继续编辑", "继续编辑", action = onKeep, modifier = Modifier.weight(1f))
                    EditorPill("放弃", "放弃修改", action = onDiscard, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun parameterSectionLabel(section: CommandSection): String = when (section) {
    CommandSection.TRIGGERS -> "触发器"
    CommandSection.CONDITIONS -> "条件"
    CommandSection.ACTIONS -> "动作"
}

/**
 * `eval %{x}` or `sh -c %{x}` deliberately executes the value a second time. That is the
 * user's explicit intent, not an escaping bug, so the editor only warns. The check is
 * intentionally textual: it is a hint, not a security boundary.
 */
internal fun shellReexecutionWarning(command: String): String? {
    if (!command.contains("%{")) return null
    val reexecution = Regex("""(?i)\b(?:eval|(?:sh|bash)\s+-c)\b""")
    if (!reexecution.containsMatchIn(command)) return null
    return "提示：命令包含 eval 或 sh -c，并且引用了 %{变量}。变量值会被当作命令再次执行，请确认这是你的意图。"
}

/** Names offered by the parameter-field variable inserter; new UI only inserts `{名字}`. */
private fun parameterVariables(state: AppState, cmd: Cmd, draft: ComponentDraft): List<VariableOption> =
    VariableCatalog.forEditor(
        state, cmd.id,
        VariablePickerContext(
            section = draft.section,
            itemId = draft.item.id,
            parentId = draft.parentId,
            branch = draft.branch,
        ),
    )
