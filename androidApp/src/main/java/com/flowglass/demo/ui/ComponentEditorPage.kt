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

    fun save() {
        if (state.saveComponentDraft()) discardOpen = false
    }

    fun requestClose() {
        if (draft.dirty) discardOpen = true else state.cancelComponentDraft()
    }

    BackHandler(enabled = true) { requestClose() }

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
            trailing = { TopBarTextButton("完成", onClick = ::save) },
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
            variables = parameterVariables(state, cmd),
            showDone = false,
        )
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
private fun parameterVariables(state: AppState, cmd: Cmd): List<VariableOption> =
    VariableCatalog.forEditor(state, cmd.id)
