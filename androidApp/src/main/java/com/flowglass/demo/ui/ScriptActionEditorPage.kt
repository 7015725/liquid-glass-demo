package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ParamSpec
import com.kyant.shapes.RoundedRectangle
import io.ma7moud3ly.nemo.NemoCodeEditor
import io.ma7moud3ly.nemo.model.CodeState
import io.ma7moud3ly.nemo.model.EditorSettings
import io.ma7moud3ly.nemo.model.EditorTheme
import io.ma7moud3ly.nemo.model.EditorThemes
import io.ma7moud3ly.nemo.model.Language
import kotlinx.coroutines.delay

/**
 * Dedicated, full-height editor for script actions. Scripts do not fit the shared parameter form:
 * they need the whole page, line numbers and a keyboard that never covers the cursor, while the
 * timeout stays one tap away.
 */
@Composable
internal fun ScriptActionEditorPage(
    item: CommandItem,
    meta: ComponentMeta,
    cmdTitle: String,
    variables: List<VariableOption>,
    hasOutputs: Boolean,
    onParameter: (String) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onOutputs: () -> Unit,
    onMove: () -> Unit,
) {
    var fields by remember(item.id) { mutableStateOf(ParameterForm.fields(meta, item.parameter)) }
    var variableMenu by remember { mutableStateOf(false) }
    var timeoutOpen by remember { mutableStateOf(false) }
    val timeoutSpec: ParamSpec? = meta.params.firstOrNull { it.key == "timeout" }

    fun update(key: String, value: String) {
        val next = fields + (key to value)
        fields = next
        ParameterForm.compose(meta, next).onSuccess(onParameter)
    }

    val script = fields["script"].orEmpty()
    val timeout = fields["timeout"].orEmpty()
    val code = remember(item.id) { CodeState(initialCode = script, language = Language.JAVASCRIPT) }
    LaunchedEffect(script) {
        if (code.code != script) code.updateText(script)
    }
    LaunchedEffect(code) {
        snapshotFlow { code.code }.collect { text -> if (text != fields["script"]) update("script", text) }
    }
    // Nemo grabs focus on its first composition; the page should open without a keyboard.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        // Nemo requests focus a few frames into the enter animation, so one early clear is not
        // enough; keep dropping it briefly instead of letting the keyboard cover the page.
        repeat(3) {
            delay(150)
            focusManager.clearFocus(force = true)
        }
    }

    val theme = remember(C.dark) { scriptEditorTheme() }
    val settings = remember(theme) {
        EditorSettings(
            theme = theme,
            fontSize = 14,
            showLineNumbers = true,
            showIndentGuides = true,
            enableAutocomplete = false,
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }

    Column(
        Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        DetailTopBar(
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
            title = { T(item.name, 20.sp, FontWeight.Bold, maxLines = 1) },
            subtitle = "脚本 · $cmdTitle",
            trailing = {
                TopBarIconButton(Ic.UpDown, "移动到其他位置", onClick = onMove)
                TopBarTextButton("完成", onClick = onDone)
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("JavaScript", 12.sp, color = C.Muted)
            Spacer(Modifier.weight(1f))
            T("${code.code.lineSequence().count()} 行", 12.sp, color = C.Muted)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedRectangle(22.dp)).background(C.Card),
        ) {
            NemoCodeEditor(
                state = code,
                modifier = Modifier.fillMaxSize().testTag("script-editor").imeAutoScroll(),
                settings = settings,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (variables.isNotEmpty()) {
                EditorPill("插入变量", "插入变量") { variableMenu = true }
            }
            if (hasOutputs) {
                EditorPill("输出", "配置输出变量", action = onOutputs)
            }
            Spacer(Modifier.weight(1f))
            if (timeoutSpec != null) {
                EditorPill(
                    if (timeout.isBlank()) "超时" else "超时 ${timeout}s",
                    "设置超时",
                ) { timeoutOpen = true }
            }
        }
    }

    if (variableMenu) {
        VariableListDialog(variables, onDismiss = { variableMenu = false }) { option ->
            val text = code.code
            val caret = code.cursorPosition.coerceIn(0, text.length)
            val snippet = "{" + option.name + "}"
            code.updateText(text.substring(0, caret) + snippet + text.substring(caret), caret + snippet.length)
            variableMenu = false
        }
    }

    if (timeoutOpen && timeoutSpec != null) {
        Dialog(
            onDismissRequest = { timeoutOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp).clip(RoundedRectangle(24.dp))
                    .background(C.Card).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("超时", 16.sp, FontWeight.SemiBold)
                T("脚本超过这个时间会被中断，并把这个动作标记为失败。", 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
                ParamField(
                    spec = timeoutSpec,
                    current = fields["timeout"].orEmpty(),
                    tag = "script-timeout",
                    onValue = { value -> update("timeout", value) },
                )
                EditorPill("完成", "关闭超时设置") { timeoutOpen = false }
            }
        }
    }
}

/** Editor palette that follows the app theme; the container supplies the glass. */
internal fun scriptEditorTheme(): EditorTheme {
    val base = if (C.dark) EditorThemes.VS_CODE_DARK else EditorThemes.GITHUB_LIGHT
    return base.copy(
        background = 0x00000000,
        gutter = 0x00000000,
        lineNumber = C.Muted.toArgbLong(),
        lineNumberActive = C.Primary.toArgbLong(),
        currentLineBackground = if (C.dark) 0x12FFFFFF else 0x0A000000,
        selection = C.Primary.copy(alpha = 0.32f).toArgbLong(),
    )
}

private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
