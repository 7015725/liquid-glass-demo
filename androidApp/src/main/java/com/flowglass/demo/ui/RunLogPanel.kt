package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.demo.system.exec.StepStatus
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun RunLogPanel(log: RunLog, backdrop: Backdrop, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorGlassPanel(backdrop, "执行日志", bottom = false, onClose = onClose) {
            T(log.title, 18.sp, FontWeight.Bold)
            T("${log.reason} · ${log.time}", 12.sp, color = C.Muted)
            if (!log.ok) {
                T("失败原因", 14.sp, FontWeight.SemiBold, C.Bad)
                T(log.detail.ifBlank { "未提供失败详情" }, 13.sp, color = C.Bad)
            } else if (log.detail.isNotBlank()) {
                T(log.detail, 13.sp, color = C.OnSurfaceVar)
            }
            if (log.steps.isEmpty()) {
                T("暂无步骤记录", 13.sp, color = C.Muted)
            }
            log.steps.forEachIndexed { index, step ->
                val (status, color) = when (step.status) {
                    StepStatus.SUCCESS -> "成功" to C.GoodText
                    StepStatus.FAILURE -> "失败" to C.Bad
                    StepStatus.SKIPPED -> "跳过" to C.Muted
                }
                Column(Modifier.fillMaxWidth().testTag("run-log-step-$index").staggerIn(index.coerceAtMost(8))
                    .clip(RoundedRectangle(24.dp)).background(C.Tonal).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        T("${index + 1}. ${step.name}", 14.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        T(status, 12.sp, FontWeight.SemiBold, color)
                    }
                    T(step.detail, 13.sp, color = C.OnSurfaceVar)
                    T("耗时 ${step.durationMs} ms", 12.sp, color = C.Muted)
                }
            }
        }
    }
}
