package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import io.ma7moud3ly.nemo.NemoCodeEditor
import io.ma7moud3ly.nemo.model.CodeState
import io.ma7moud3ly.nemo.model.EditorSettings
import io.ma7moud3ly.nemo.model.EditorTheme
import io.ma7moud3ly.nemo.model.EditorThemes
import io.ma7moud3ly.nemo.model.Language

/**
 * JavaScript editor for script parameters. The chrome (card, tool row, variable insertion) stays
 * the same as every other parameter; only the text surface is a syntax-highlighted editor.
 *
 * The editor background is transparent so the glass card behind it keeps showing through; the
 * theme is rebuilt when the app palette switches between light and dark.
 */
@Composable
internal fun ScriptCodeField(
    value: String,
    tag: String,
    variables: List<VariableOption>,
    onValue: (String) -> Unit,
    canConfigureOutputs: Boolean = false,
    onConfigureOutputs: (() -> Unit)? = null,
) {
    val state = remember { CodeState(initialCode = value, language = Language.JAVASCRIPT) }
    var menu by remember { mutableStateOf(false) }
    val theme = remember(C.dark) { scriptTheme() }
    val settings = remember(theme) {
        EditorSettings(
            theme = theme,
            fontSize = 13,
            showLineNumbers = true,
            showIndentGuides = false,
            enableAutocomplete = false,
            contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 10.dp, bottom = 12.dp),
        )
    }
    // The editor owns its text; push external edits (rule load, migration) without fighting typing.
    LaunchedEffect(value) {
        if (state.code != value) state.updateText(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.code }.collect { text -> onValue(text) }
    }
    // Nemo asks for focus on its first composition, which would pop the IME the moment the
    // parameter page opens. Drop that request; tapping the editor still focuses it normally.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        focusManager.clearFocus(force = true)
    }

    Column(Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card)) {
        if (variables.isNotEmpty() || (canConfigureOutputs && onConfigureOutputs != null)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T("JavaScript", 11.sp, color = C.Muted)
                Spacer(Modifier.weight(1f))
                if (variables.isNotEmpty()) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedRectangle(13.dp)).background(C.Tonal)
                            .testTag("$tag-insert-variable")
                            .editorAction("插入变量") { menu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        LineIcon(Ic.Variable, C.Primary, 18.dp)
                    }
                }
                if (canConfigureOutputs && onConfigureOutputs != null) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedRectangle(13.dp)).background(C.Tonal)
                            .testTag("$tag-configure-outputs")
                            .editorAction("配置输出变量", onConfigureOutputs),
                        contentAlignment = Alignment.Center,
                    ) {
                        LineIcon(Ic.Output, C.Primary, 18.dp)
                    }
                }
            }
        }
        // Grow with the script instead of leaving a 240dp hole under two lines; long scripts
        // scroll inside the editor up to the cap.
        val lineCount = state.code.lineSequence().count()
        val editorHeight = (lineCount * 20 + 44).dp.coerceIn(120.dp, 360.dp)
        NemoCodeEditor(
            state = state,
            modifier = Modifier.fillMaxWidth().height(editorHeight).testTag(tag).imeAutoScroll(),
            settings = settings,
        )
    }
    if (menu) {
        VariableListDialog(variables, onDismiss = { menu = false }) { option ->
            val text = state.code
            val caret = state.cursorPosition.coerceIn(0, text.length)
            val snippet = "{" + option.name + "}"
            state.updateText(
                text.substring(0, caret) + snippet + text.substring(caret),
                caret + snippet.length,
            )
            menu = false
        }
    }
}

private fun scriptTheme(): EditorTheme {
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
