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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.engine.ComponentMeta
import com.kyant.shapes.RoundedRectangle

private data class ConditionGroup(val label: String, val operators: List<Pair<String, String>>)

private val conditionGroups = listOf(
    ConditionGroup("数字比较", listOf("gt" to "大于", "ge" to "大于等于", "lt" to "小于", "le" to "小于等于")),
    ConditionGroup("相等", listOf("eq" to "等于", "ne" to "不等于")),
    ConditionGroup("文本", listOf("contains" to "包含", "not_contains" to "不包含")),
    ConditionGroup("状态", listOf("unset" to "未设置", "set" to "已设置", "empty" to "为空", "not_empty" to "不为空")),
    ConditionGroup("文件", listOf("file_exists" to "文件存在", "file_not_exists" to "文件不存在")),
)

private val operatorLabels: Map<String, String> =
    conditionGroups.flatMap { it.operators }.toMap()

private fun operatorId(raw: String): String =
    operatorLabels.entries.firstOrNull { it.value == raw }?.key
        ?: raw.takeIf { it in operatorLabels } ?: "eq"

private fun needsRight(op: String): Boolean = op in setOf(
    "gt", "ge", "lt", "le", "eq", "ne", "contains", "not_contains",
)

@Composable
internal fun VariableConditionEditor(
    meta: ComponentMeta,
    item: CommandItem,
    onParameter: (String) -> Unit,
    variables: List<VariableOption>,
) {
    var fields by remember(item.id) { mutableStateOf(ParameterForm.fields(meta, item.parameter)) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }

    fun update(key: String, value: String) {
        val next = fields + (key to value)
        fields = next
        ParameterForm.compose(meta, next)
            .onSuccess { onParameter(it); error = null }
            .onFailure { error = it.message?.replace('\n', ' ')?.take(160) }
    }

    val op = operatorId(fields["op"].orEmpty().ifEmpty { "eq" })
    val isFile = op == "file_exists" || op == "file_not_exists"
    val left = fields["left"].orEmpty()
    val needsValue = needsRight(op)
    var picker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T(if (isFile) "文件路径或变量" else "变量", 14.sp, FontWeight.SemiBold)
        VariableSelectorField(
            value = left,
            onValue = { update("left", it) },
            onPick = { picker = true },
            placeholder = if (isFile) "选择变量，或直接填写路径" else "请选择变量",
        )
        T(
            if (isFile) "路径不能包含换行或 NUL；有 Root 时优先用 Root 执行 test -e。" else
                "可从内置、局部、全局变量中选择，也可以手动写 {名字}。",
            12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp,
        )

        T("判断方式", 14.sp, FontWeight.SemiBold)
        ConditionOperatorPicker(op) { update("op", it) }

        if (needsValue) {
            T(if (op in setOf("gt", "ge", "lt", "le")) "比较值（整数）" else "比较值", 14.sp, FontWeight.SemiBold)
            VariableTextField(fields["right"].orEmpty(), "condition-right", variables, multiline = false) {
                update("right", it)
            }
            T("文本比较不需要加引号。", 12.sp, color = C.OnSurfaceVar)
        }

        error?.let { T("参数无效：$it", 12.sp, color = C.Bad) }
    }

    if (picker) {
        VariableListDialog(variables, onDismiss = { picker = false }) { option ->
            update("left", "{${option.name}}")
            picker = false
        }
    }
}

@Composable
private fun VariableSelectorField(
    value: String,
    onValue: (String) -> Unit,
    onPick: () -> Unit,
    placeholder: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedRectangle(20.dp))
                .background(C.Card).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth().testTag("condition-left"),
                textStyle = TextStyle(color = C.OnSurface, fontSize = 15.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(C.Primary),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) T(placeholder, 14.sp, color = C.Muted)
                        inner()
                    }
                },
            )
        }
        EditorPill("选择", "选择变量", modifier = Modifier.padding(start = 8.dp), action = onPick)
    }
}

@Composable
private fun ConditionOperatorPicker(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedRectangle(20.dp)).background(C.Card)
            .testTag("condition-op").pressable { open = true }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(operatorLabels[current] ?: current, 15.sp, color = C.OnSurface, modifier = Modifier.weight(1f))
        T("展开", 12.sp, color = C.Primary)
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedRectangle(24.dp))
                        .background(C.Card).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    T("判断方式", 17.sp, FontWeight.SemiBold)
                    conditionGroups.forEach { group ->
                        T(group.label, 12.sp, color = C.Muted, modifier = Modifier.padding(top = 6.dp))
                        group.operators.forEach { (id, label) ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedRectangle(14.dp))
                                    .background(if (id == current) C.PrimarySoft else C.Tonal)
                                    .testTag("condition-op-$id")
                                    .pressable { onSelect(id); open = false }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                T(label, 14.sp, color = if (id == current) C.Primary else C.OnSurface,
                                    modifier = Modifier.weight(1f))
                                if (id == current) T("已选", 11.sp, color = C.Primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
