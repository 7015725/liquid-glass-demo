package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.OutputBinding
import com.flowglass.engine.OutputCatalog
import com.flowglass.engine.VariableName
import com.flowglass.engine.VariableType
import com.flowglass.engine.methodhook.AppMethodCall
import com.kyant.shapes.RoundedRectangle

/**
 * Component-level output editor. It edits a copy and only calls [onSave] after every name is
 * valid and at least one row is enabled, so the command is never left with an invalid binding.
 */
@Composable
internal fun ComponentOutputPage(
    item: CommandItem,
    section: CommandSection,
    cmdTitle: String,
    onBack: () -> Unit,
    onSave: (List<OutputBinding>) -> Unit,
    onConfigureExtractions: (() -> Unit)? = null,
    validateName: ((String) -> String?)? = null,
) {
    val meta = item.typeId?.let { BuiltInComponents.registry().find(it) } ?: return
    if (meta.kind == ComponentKind.CONDITION) return
    val specs = remember(meta.typeId) { OutputCatalog.allOutputs(meta) }
    var outputs by remember(item.id) {
        mutableStateOf(
            (if (item.outputs.isEmpty()) OutputCatalog.defaultBindings(meta) else item.outputs)
                .filter { binding -> specs.any { it.key == binding.key } },
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun update(index: Int, binding: OutputBinding) {
        outputs = outputs.toMutableList().also { it[index] = binding }
        error = null
    }

    Column(
        Modifier.fillMaxSize().background(C.Bg).testTag("component-output-page")
            .statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailTopBar(
            onBack = onBack,
            title = { T("输出变量", 20.sp, FontWeight.Bold, maxLines = 1) },
            subtitle = "${meta.name} · ${if (section == CommandSection.TRIGGERS) "触发器" else "动作"} · $cmdTitle",
            trailing = {
                TopBarTextButton("保存", onClick = {
                    val issues = validateOutputs(outputs, meta, validateName)
                    if (issues != null) error = issues else onSave(outputs)
                })
            },
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Tonal).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            T(
                if (section == CommandSection.TRIGGERS) "触发时写入，条件和动作可使用。" else "该动作完成后写入，后续步骤可使用。",
                13.sp, color = C.OnSurfaceVar, lineHeight = 19.sp,
            )
            T("输出变量只读，不会被「设置变量」或脚本改写。", 12.sp, color = C.Muted)
        }

        for ((index, binding) in outputs.withIndex()) {
            val spec = specs.firstOrNull { it.key == binding.key } ?: continue
            OutputRow(
                spec = spec,
                binding = binding,
                index = index,
                enabledCount = outputs.count { it.enabled },
                onChange = { update(index, it) },
                onAlias = {
                    outputs = outputs + binding.copy(name = "", enabled = true)
                    error = null
                },
                onRestore = { update(index, binding.copy(name = OutputCatalog.defaultName(meta, spec))) },
            )
        }

        if (item.typeId == AppMethodCall.TYPE_ID) {
            val extractions = remember(item.parameter) {
                OutputCatalog.methodExtractionNames(item.typeId.orEmpty(), parseParams(item))
            }
            if (extractions.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    T("提取输出（只读）", 15.sp, FontWeight.SemiBold)
                    T("这些名字来自「配置提取数据」，在输出页不能改名或删除。", 12.sp, color = C.OnSurfaceVar)
                    extractions.forEach { name -> T("· $name", 13.sp) }
                    if (onConfigureExtractions != null) {
                        EditorPill("去配置提取数据", "去配置提取数据", action = onConfigureExtractions)
                    }
                }
            }
        }

        error?.let { T(it, 13.sp, color = C.Bad, lineHeight = 19.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EditorPill("取消", "取消", action = onBack, modifier = Modifier.weight(1f))
            EditorPill("保存", "保存输出变量", action = {
                val issues = validateOutputs(outputs, meta, validateName)
                if (issues != null) error = issues else onSave(outputs)
            }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun OutputRow(
    spec: com.flowglass.engine.OutputSpec,
    binding: OutputBinding,
    index: Int,
    enabledCount: Int,
    onChange: (OutputBinding) -> Unit,
    onAlias: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(14.dp)
            .testTag("output-row-${spec.key}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(spec.label, 15.sp, FontWeight.SemiBold)
                T(typeLabel(spec.type), 12.sp, color = C.Muted)
            }
            EditorPill(
                if (binding.enabled) "已启用" else "已禁用",
                if (binding.enabled) "禁用输出" else "启用输出",
                action = {
                    if (binding.enabled && enabledCount <= 1) return@EditorPill
                    onChange(binding.copy(enabled = !binding.enabled))
                },
            )
        }
        BasicTextField(
            binding.name,
            { onChange(binding.copy(name = it)) },
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clip(RoundedRectangle(16.dp)).background(C.Tonal)
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .testTag("output-name-${spec.key}-$index"),
            textStyle = TextStyle(color = C.OnSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(C.Primary),
            singleLine = true,
        )
        if (spec.description.isNotBlank()) T(spec.description, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorPill("添加别名", "为${spec.label}添加别名", action = onAlias)
            EditorPill("恢复默认", "恢复默认名", action = onRestore)
        }
    }
}

private fun validateOutputs(
    outputs: List<OutputBinding>,
    meta: ComponentMeta,
    validateName: ((String) -> String?)? = null,
): String? {
    if (outputs.none { it.enabled }) return "至少要启用一个输出"
    val seen = mutableSetOf<String>()
    for (binding in outputs) {
        if (!VariableName.isValid(binding.name)) {
            return VariableName.error(binding.name) ?: "变量名「${binding.name}」无效"
        }
        if (!seen.add(binding.name)) return "变量名重复：${binding.name}"
        validateName?.invoke(binding.name)?.let { return it }
    }
    OutputCatalog.validateBindings(meta, outputs).firstOrNull()?.let { return it }
    return null
}

private fun parseParams(item: CommandItem): Map<String, kotlinx.serialization.json.JsonElement> {
    val meta = item.typeId?.let { BuiltInComponents.registry().find(it) } ?: return emptyMap()
    return runCatching { com.flowglass.engine.ParameterCodec.parseComponent(meta, item.parameter) }
        .getOrDefault(emptyMap())
}

private fun typeLabel(type: VariableType): String = when (type) {
    VariableType.INT -> "整数"
    VariableType.BOOLEAN -> "真假"
    VariableType.TEXT -> "文本"
    VariableType.INT_LIST -> "整数列表"
    VariableType.BOOLEAN_LIST -> "真假列表"
    VariableType.TEXT_LIST -> "文本列表"
}
