package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.demo.system.exec.StepStatus
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

private val HistoryFilters = listOf("全部", "成功", "失败", "跳过")

/**
 * Full execution history, opened from the home "最近执行" card. The compact card only shows the
 * newest few records; this panel keeps filters and per-step detail without nesting a second dialog.
 */
@Composable
internal fun HistoryPanel(state: AppState, onClose: () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    var filter by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val all = state.logs
    val list = when (filter) {
        1 -> all.filter { it.ok && !it.skipped }
        2 -> all.filter { !it.ok && !it.skipped }
        3 -> all.filter { it.skipped }
        else -> all
    }
    val okCount = all.count { it.ok && !it.skipped }
    val failed = all.count { !it.ok && !it.skipped }
    val skipped = all.count { it.skipped }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorGlassPanel(backdrop, "执行记录", bottom = true, onClose = onClose) {
            Row(Modifier.fillMaxWidth().testTag("history-panel"), verticalAlignment = Alignment.CenterVertically) {
                T("共 ${all.size} 条", 13.sp, FontWeight.Medium, C.OnSurfaceVar)
                Spacer(Modifier.weight(1f))
                T("成功 $okCount · 失败 $failed · 跳过 $skipped", 12.sp, color = C.Muted)
            }
            FilterChips(HistoryFilters, filter, { filter = it }, Modifier.testTag("history-filters"))
            if (list.isEmpty()) {
                EmptyState(
                    if (filter == 0) Ic.Timer else Ic.Search,
                    if (filter == 0) "还没有执行记录" else "没有${HistoryFilters[filter]}记录",
                    if (filter == 0) "运行一条一键指令或手动测试自动指令后，这里会按时间记录每一步的结果。"
                    else "切换到「全部」可以查看其它记录。",
                    modifier = Modifier.testTag("history-empty"),
                )
            } else {
                list.forEach { log -> HistoryRow(log, expanded == log.id) { expanded = if (expanded == log.id) null else log.id } }
            }
        }
    }
}

@Composable
private fun HistoryRow(log: RunLog, open: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().testTag("history-row-${log.id}")
            .clip(RoundedRectangle(20.dp)).background(C.Card)
            .editorAction("${if (open) "收起" else "展开"}执行记录：${log.title}") { onToggle() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(log.title, 15.sp, FontWeight.SemiBold, maxLines = 1)
                T("${log.reason} · ${log.time}", 12.sp, color = C.Muted, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            when {
                log.skipped -> BadgePill("跳过", C.OnSurfaceVar, C.Tonal)
                log.ok -> BadgePill("成功", C.GoodText, C.GoodSoft)
                else -> BadgePill("失败", C.Bad, C.BadSoft)
            }
        }
        if (!log.ok && !log.skipped && log.detail.isNotBlank()) {
            T(log.detail, 12.sp, color = C.Bad, maxLines = if (open) Int.MAX_VALUE else 2, lineHeight = 18.sp)
        }
        if (log.skipped && log.detail.isNotBlank()) {
            T(log.detail, 12.sp, color = C.OnSurfaceVar, maxLines = if (open) Int.MAX_VALUE else 2, lineHeight = 18.sp)
        }
        if (open) {
            if (log.steps.isEmpty()) {
                T("没有步骤记录", 12.sp, color = C.Muted)
            } else {
                log.steps.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth().testTag("history-step-$index"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(26.dp).clip(Capsule()).background(
                            when (step.status) {
                                StepStatus.SUCCESS -> C.Good
                                StepStatus.FAILURE -> C.Bad
                                StepStatus.SKIPPED -> C.Muted
                            }
                        ).padding(0.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            T("${index + 1}. ${step.name}", 13.sp, FontWeight.Medium, C.OnSurface, maxLines = 1)
                            T(step.detail, 12.sp, color = C.OnSurfaceVar, lineHeight = 17.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        T("${step.durationMs} ms", 11.sp, color = C.Muted, tabular = true)
                    }
                }
            }
            if (log.ok && log.detail.isNotBlank()) {
                T(log.detail, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
            }
            T("点击收起", 11.sp, color = C.Muted)
        }
    }
}
