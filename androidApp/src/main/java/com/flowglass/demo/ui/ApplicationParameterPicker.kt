package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.demo.system.SysTick
import com.flowglass.demo.system.exec.ApplicationCatalog
import com.flowglass.demo.system.exec.LaunchableApps
import com.flowglass.engine.ParamSpec
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.CancellationException

/**
 * A compact application field plus the shared full-screen list page. The field itself has no
 * application list, search or root branch; all of that lives in [ApplicationListPage].
 */
@Composable
internal fun ApplicationParameterPicker(
    current: String,
    spec: ParamSpec,
    stateKey: Any?,
    onParameter: (String) -> Unit,
) {
    val context = LocalContext.current
    var open by rememberSaveable(stateKey) { mutableStateOf(false) }
    var display by remember(current) {
        mutableStateOf(current.ifBlank { if (spec.required) "未选择应用" else "任意应用（留空）" })
    }
    LaunchedEffect(current) {
        if (current.isNotBlank()) {
            display = try {
                ApplicationCatalog.label(context, current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                current
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedRectangle(22.dp))
                .background(C.Card).editorAction("选择应用") { open = true }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(display, 15.sp, FontWeight.Medium, maxLines = 1)
                T(current.ifBlank { "未选择" }, 12.sp, color = C.OnSurfaceVar, maxLines = 1)
            }
            T("选择", 13.sp, FontWeight.SemiBold, C.Primary)
        }
        T(
            if (spec.applicationScope == com.flowglass.engine.ApplicationScope.CURRENT_PROFILE) {
                "只提供当前资料的主应用；应用前台触发器暂不能可靠识别分身。"
            } else {
                "主应用和分身分别显示；分身保存为 package@userId。"
            },
            12.sp,
            color = C.OnSurfaceVar,
            lineHeight = 18.sp,
        )
    }

    if (open) {
        Dialog(
            onDismissRequest = { open = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ApplicationListPage(
                current = current,
                scope = spec.applicationScope,
                allowEmpty = !spec.required,
                onDismiss = { open = false },
                onPick = { value ->
                    open = false
                    onParameter(value)
                },
            )
        }
    }
}

@Composable
internal fun itemParameterLabel(section: CommandSection, item: CommandItem): String {
    if (section == CommandSection.ACTIONS && item.typeId == "action.post_notification") {
        return notificationDisplayText(item.parameter)
    }
    val context = LocalContext.current
    val tick = SysTick.tick
    var label by remember(item.parameter) { mutableStateOf(item.parameter) }
    LaunchedEffect(context, tick, section, item.name, item.parameter) {
        label = when {
            section == CommandSection.ACTIONS && item.name == "启动应用" -> {
                try { LaunchableApps.label(context, item.parameter) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { item.parameter }
            }
            item.typeId == "trigger.time" -> item.parameter.split('|').joinToString(" · ")
            item.typeId == "trigger.battery_level" -> item.parameter.split('|').joinToString(" ").trim()
            // Same plain-language mapping as the block summaries, so cards and rows agree.
            else -> ActionSummary.parameterText(item).ifEmpty { item.parameter }
        }
    }
    return label.ifEmpty { "点按设置参数" }
}
