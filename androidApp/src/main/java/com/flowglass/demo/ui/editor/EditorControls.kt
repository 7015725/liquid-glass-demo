package com.flowglass.demo.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.demo.liquid.LiquidButton
import com.flowglass.demo.ui.C
import com.flowglass.demo.ui.PressCard
import com.flowglass.demo.ui.T
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle

private val LocalEditorBackdrop = staticCompositionLocalOf<Backdrop> { error("EditorFrame is required") }

@Composable
internal fun EditorFrame(
    title: String,
    back: () -> Unit,
    action: String? = null,
    actionTag: String = "editor-done",
    onAction: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
            .background(Brush.verticalGradient(listOf(C.PrimarySoft, C.Bg, C.Tonal))))
        CompositionLocalProvider(LocalEditorBackdrop provides backdrop) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorButton("返回", "editor-back", onClick = back)
                    T(title, 20.sp, FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                    if (action != null) EditorButton(action, actionTag, selected = true, onClick = onAction)
                }
                content()
            }
        }
    }
}

@Composable
internal fun EditorButton(label: String, tag: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LiquidButton(onClick, LocalEditorBackdrop.current,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag(tag)
            .semantics { contentDescription = label; this.selected = selected },
        isInteractive = false,
        surfaceColor = if (selected) C.Primary else C.Card.copy(alpha = 0.85f)) {
        T(label, 14.sp, FontWeight.SemiBold, if (selected) Color.White else C.OnSurfaceVar)
    }
}

@Composable
internal fun EditorCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    PressCard(Modifier.fillMaxWidth(), radius = 26.dp, contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) T(title, 17.sp, FontWeight.Bold, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
internal fun EditorField(label: String, value: String, onChange: (String) -> Unit, tag: String,
                         multiline: Boolean = false, numeric: Boolean = false, hint: String = "", readOnly: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(label, 13.sp, FontWeight.Medium, C.OnSurfaceVar)
        BasicTextField(value, onChange, singleLine = !multiline, readOnly = readOnly,
            textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp), cursorBrush = SolidColor(C.Primary),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 96.dp else 52.dp)
                .clip(RoundedRectangle(16.dp)).background(C.Tonal).testTag(tag)
                .semantics { contentDescription = label + if (hint.isBlank()) "" else "，$hint" }
                .padding(14.dp))
        if (hint.isNotBlank()) T(hint, 12.sp, color = C.OnSurfaceVar)
    }
}

@Composable
internal fun EditorChoices(label: String, choices: List<Pair<String, String>>, value: String, tag: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank()) T(label, 13.sp, color = C.OnSurfaceVar)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (id, name) -> EditorButton(name, "$tag-$id", selected = value == id) { onChange(id) } }
        }
    }
}

@Composable
internal fun EditorErrors(text: String, tag: String = "validation-errors") {
    if (text.isNotBlank()) T(text, 13.sp, color = C.BadText,
        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState())
            .testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun EditorDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // Own nested form state in the caller's registry, not the transient dialog window.
    val state = rememberSaveableStateHolder()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        state.SaveableStateProvider("content") { content() }
    }
}

@Composable
internal fun DiscardDialog(onStay: () -> Unit, onDiscard: () -> Unit, message: String = "修改尚未保存，是否放弃？") {
    Dialog(onDismissRequest = onStay) {
        val backdrop = rememberLayerBackdrop()
        CompositionLocalProvider(LocalEditorBackdrop provides backdrop) {
            Box(Modifier.layerBackdrop(backdrop).background(C.Card, RoundedRectangle(26.dp))) {
                EditorCard("放弃修改？") {
                    T(message)
                    EditorButton("继续编辑", "discard-stay", onClick = onStay)
                    EditorButton("放弃修改", "discard-confirm", onClick = onDiscard)
                }
            }
        }
    }
}
