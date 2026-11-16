package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.engine.BuiltInActions
import com.flowglass.engine.ParameterCodec
import com.kyant.shapes.RoundedRectangle
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 标题与内容分开编辑；通知方式与 Toast 时长是同一组件的附加参数。
 * 旧的 `标题|内容` 文本仍按系统通知解析；只有非默认方式才写成 JSON 扩展格式。
 */
@Composable
internal fun NotificationParameterEditor(
    item: CommandItem,
    commandTitle: String,
    onParameter: (String) -> Unit,
    onDone: () -> Unit,
    showDone: Boolean = true,
) {
    val meta = remember { BuiltInActions.metadata.single { it.typeId == "action.post_notification" } }
    val initial = remember(item.id) {
        runCatching { ParameterCodec.parseComponent(meta, item.parameter) }.getOrNull().orEmpty()
    }
    var title by remember(item.id) { mutableStateOf(initial["title"]?.jsonPrimitive?.content.orEmpty()) }
    var body by remember(item.id) { mutableStateOf(initial["content"]?.jsonPrimitive?.content.orEmpty()) }
    var mode by remember(item.id) { mutableStateOf(initial["mode"]?.jsonPrimitive?.content ?: "system") }
    var duration by remember(item.id) { mutableStateOf(initial["toastDuration"]?.jsonPrimitive?.content ?: "short") }
    val invalidTitle = '|' in title

    fun emit() {
        if (invalidTitle) return
        onParameter(ParameterCodec.formatComponent(meta, mapOf(
            "title" to JsonPrimitive(title),
            "content" to JsonPrimitive(body),
            "mode" to JsonPrimitive(mode),
            "toastDuration" to JsonPrimitive(duration),
        )))
    }

    T("通知标题", 14.sp, FontWeight.Medium)
    NotificationField(title, "notification-title", "通知标题", 56.dp) { value ->
        title = value
        emit()
    }
    T("留空使用指令标题：$commandTitle", 12.sp, color = C.Muted)
    T("通知内容", 14.sp, FontWeight.Medium)
    NotificationField(body, "notification-body", "通知正文", 100.dp) { value ->
        body = value
        emit()
    }
    T("标题不能含 |。内容支持换行和 |。", 12.sp, color = C.OnSurfaceVar)

    val modes = meta.params.single { it.key == "mode" }.options
    T("通知方式", 14.sp, FontWeight.Medium)
    SegmentedSelector(
        modes.map { modeOption ->
            (when (modeOption.value) {
                "toast" -> Ic.Message
                "both" -> Ic.Play
                else -> Ic.Bell
            }) to modeOption.label
        },
        modes.indexOfFirst { it.value == mode }.coerceAtLeast(0),
    ) { index ->
        mode = modes[index].value
        emit()
    }

    if (mode != "system") {
        val durations = meta.params.single { it.key == "toastDuration" }.options
        T("Toast 显示时长", 14.sp, FontWeight.Medium)
        SegmentedSelector(
            durations.map { Ic.Timer to it.label },
            durations.indexOfFirst { it.value == duration }.coerceAtLeast(0),
        ) { index ->
            duration = durations[index].value
            emit()
        }
        T("Toast 内容为「标题 · 内容」；标题为空时只显示内容。", 12.sp, color = C.Muted)
    }

    if (invalidTitle) {
        T("通知标题不能包含 |；已保留上一合法参数", 13.sp, color = C.Bad)
    }
    if (showDone) EditorPill("完成", "完成", action = { if (!invalidTitle) onDone() })
}

@Composable
private fun NotificationField(
    value: String,
    tag: String,
    label: String,
    minHeight: androidx.compose.ui.unit.Dp,
    onValue: (String) -> Unit,
) {
    BasicTextField(value, onValue,
        Modifier.fillMaxWidth().heightIn(min = minHeight).testTag(tag)
            .semantics { contentDescription = label }
            .clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp),
        textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 24.sp),
        cursorBrush = SolidColor(C.Primary))
}
