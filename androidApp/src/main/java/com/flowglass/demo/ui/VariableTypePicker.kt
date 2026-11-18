package com.flowglass.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.engine.VariableType

/** ParameterForm renders enums with their Chinese labels; accept either label or storage key. */
internal fun variableTypeOfField(raw: String): VariableType =
    VariableType.fromStorage(raw) ?: when (raw.trim()) {
        "整数" -> VariableType.INT
        "真假" -> VariableType.BOOLEAN
        "文本" -> VariableType.TEXT
        "整数列表" -> VariableType.INT_LIST
        "真假列表" -> VariableType.BOOLEAN_LIST
        "文本列表" -> VariableType.TEXT_LIST
        else -> VariableType.TEXT
    }

/** The only type picker in the editor: two rows, six Chinese labels. */
@Composable
internal fun VariableTypePicker(
    selected: VariableType,
    onSelect: (VariableType) -> Unit,
    tagPrefix: String = "variable-type",
) {
    val basic = listOf(VariableType.INT, VariableType.BOOLEAN, VariableType.TEXT)
    val lists = listOf(VariableType.INT_LIST, VariableType.BOOLEAN_LIST, VariableType.TEXT_LIST)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T("基本类型", 12.sp, color = C.Muted)
        FilterChips(
            basic.map(VariableCatalog::typeLabel),
            if (selected in basic) basic.indexOf(selected) else -1,
            onSelect = { onSelect(basic[it]) },
            modifier = Modifier.testTag("$tagPrefix-basic"),
        )
        T("列表类型", 12.sp, color = C.Muted)
        FilterChips(
            lists.map(VariableCatalog::typeLabel),
            if (selected in lists) lists.indexOf(selected) else -1,
            onSelect = { onSelect(lists[it]) },
            modifier = Modifier.testTag("$tagPrefix-list"),
        )
    }
}
