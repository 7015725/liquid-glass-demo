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
import com.kyant.shapes.RoundedRectangle

/**
 * 标题与内容分开编辑，存储格式仍是「标题|内容」。
 * 本地草稿允许标题暂时非法而不写回；只有标题合法时才提交完整草稿。
 */
@Composable
internal fun NotificationParameterEditor(
    item: CommandItem,
    commandTitle: String,
    onParameter: (String) -> Unit,
    onDone: () -> Unit,
) {
    // Like NativeTasks, split once on the first separator. Merely opening must not re-encode legacy text.
    val split = remember(item.id) { item.parameter.indexOf('|') }
    var title by remember(item.id) { mutableStateOf(if (split < 0) "" else item.parameter.substring(0, split)) }
    var body by remember(item.id) { mutableStateOf(if (split < 0) item.parameter else item.parameter.substring(split + 1)) }
    val invalidTitle = '|' in title

    T("通知标题", 14.sp, FontWeight.Medium)
    NotificationField(title, "notification-title", "通知标题", 56.dp) { value ->
        title = value
        if ('|' !in value) onParameter("$value|$body")
    }
    T("留空使用指令标题：$commandTitle", 12.sp, color = C.Muted)
    T("通知内容", 14.sp, FontWeight.Medium)
    NotificationField(body, "notification-body", "通知正文", 100.dp) { value ->
        body = value
        if (!invalidTitle) onParameter("$title|$value")
    }
    T("标题不能含 |。内容支持换行和 |。", 12.sp, color = C.OnSurfaceVar)
    if (invalidTitle) {
        T("通知标题不能包含 |；已保留上一合法参数", 13.sp, color = C.Bad)
    }
    EditorPill("完成", "完成", action = { if (!invalidTitle) onDone() })
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
