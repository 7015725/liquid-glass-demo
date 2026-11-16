package com.flowglass.demo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.engine.OnError
import com.flowglass.engine.ParamSpec
import com.flowglass.engine.ParamType
import com.kyant.shapes.RoundedRectangle

/**
 * 动作参数页：从指令编辑页的任务行直接进入，按 ParamSpec 生成表单。
 * 排序、增删仍在指令编辑页完成；执行语义见 docs/task-flow.md。
 */
@Composable
internal fun ActionEditorHost(state: AppState) {
    val cmd = state.actionEditingCmd?.let(state::find)
    val item = cmd?.actions?.findItem(state.actionEditing ?: -1)
    var lastCmd by remember { mutableStateOf<Cmd?>(null) }
    var lastItem by remember { mutableStateOf<CommandItem?>(null) }
    if (cmd != null && item != null) {
        lastCmd = cmd
        lastItem = item
    }
    val show = cmd != null && item != null
    AnimatedVisibility(show,
        enter = fadeIn(effect()) + scaleIn(spatial(), initialScale = 0.96f),
        exit = fadeOut(effect()) + scaleOut(spatial(), targetScale = 0.96f)) {
        val shownCmd = cmd ?: lastCmd
        val shownItem = item ?: lastItem
        if (shownCmd != null && shownItem != null) {
            key(shownItem.id) { ActionEditorPage(state, shownCmd, shownItem) }
        }
    }
}

@Composable
private fun ActionEditorPage(state: AppState, cmd: Cmd, item: CommandItem) {
    fun close() {
        state.actionEditing = null
        state.actionEditingCmd = null
    }
    BackHandler(enabled = true) { close() }
    Column(Modifier.fillMaxSize().background(C.Bg).testTag("action-editor")
        .statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailTopBar(
            onBack = { close() },
            title = { T(cmd.title, 20.sp, FontWeight.Bold, maxLines = 1) },
            subtitle = "动作参数",
        )
        ActionParameterEditor(
            section = CommandSection.ACTIONS,
            item = item,
            onParameter = { state.setItemParameter(cmd.id, CommandSection.ACTIONS, item.id, it) },
            onDone = ::close,
            commandTitle = cmd.title,
            targets = (state.autoCmds + state.oneTaps).map { it.title },
        )
    }
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
