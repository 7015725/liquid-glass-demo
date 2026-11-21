package com.flowglass.demo.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share text or export one command as a JSON file, both from the list long-press menu. */
@Composable
internal fun ShareCommandDialog(state: AppState, cmd: Cmd, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingJson by remember(cmd.id) { mutableStateOf<String?>(null) }
    var notice by remember(cmd.id) { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = pendingJson
        pendingJson = null
        if (uri != null && text != null) {
            scope.launch {
                val error = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                            writer.write(text)
                        } ?: error("无法打开目标文件")
                    }
                }.exceptionOrNull()
                notice = if (error == null) "指令文件已导出" else "导出失败：${error.message.orEmpty()}"
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp).clip(RoundedRectangle(28.dp))
                .background(C.Card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("分享「${cmd.title}」", 19.sp, FontWeight.Bold)
            T("可以把可读文本发给朋友，或导出带格式版本的 JSON 文件。", 13.sp, color = C.Muted)
            DialogAction("分享文本", "把指令摘要发送到其他应用") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, commandShareText(cmd))
                runCatching {
                    context.startActivity(
                        Intent.createChooser(send, "分享指令").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                onDismiss()
            }
            DialogAction("导出 JSON 文件", "使用系统文件选择器保存") {
                pendingJson = runCatching { exportCommandJson(state, cmd) }
                    .onFailure { notice = "导出失败：${it.message.orEmpty()}" }
                    .getOrNull()
                pendingJson?.let {
                    runCatching { exportLauncher.launch(safeFileName(cmd.title) + ".json") }
                        .onFailure { error -> notice = "无法打开文件选择器：${error.message.orEmpty()}" }
                }
            }
            notice?.let { T(it, 12.sp, color = C.Muted) }
            DialogAction("取消", null, onClick = onDismiss)
        }
    }
}

@Composable
private fun DialogAction(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp)).background(C.Tonal)
            .pressable { onClick() }.padding(14.dp),
    ) {
        T(title, 15.sp, FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            T(subtitle, 12.sp, color = C.Muted)
        }
    }
}

internal fun safeFileName(value: String): String =
    value.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifBlank { "command" }.take(48)

/** Preview is deliberately shown before any command or backup touches live state. */
@Composable
internal fun ImportPreviewDialog(
    preview: ImportPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmBusy: Boolean = false,
    statusMessage: String? = null,
) {
    val isBackup = preview.kind == ImportKind.BACKUP
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp).clip(RoundedRectangle(28.dp))
                .background(C.Card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T(if (isBackup) "恢复备份" else "导入预览", 20.sp, FontWeight.Bold)
            T(
                if (isBackup) "恢复会替换当前全部指令、变量和设置；恢复前会自动备份当前数据。"
                else "以下内容来自不可信文件，确认前不会写入。",
                13.sp, color = C.Muted, lineHeight = 19.sp,
            )
            statusMessage?.let { T(it, 12.sp, color = C.Bad, lineHeight = 18.sp) }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                preview.commands.forEach { command ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp)).background(C.Tonal)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        T(command.title, 15.sp, FontWeight.SemiBold)
                        T(
                            (if (command.auto) "自动指令" else "一键指令") +
                                " · 触发器 ${command.triggerCount} · 条件 ${command.conditionCount} · 任务 ${command.actionCount}",
                            12.sp, color = C.Muted,
                        )
                        previewLine("触发器", command.triggers)
                        previewLine("条件", command.conditions)
                        previewLine("任务", command.actions)
                        if (command.rootRequired.isNotEmpty()) {
                            T(
                                "需要 Root：${command.rootRequired.joinToString("、")}",
                                12.sp, color = C.Bad, lineHeight = 17.sp,
                            )
                        }
                        if (command.sensitive.isNotEmpty()) {
                            T(
                                "敏感动作：${command.sensitive.joinToString("、")}",
                                12.sp, color = C.Bad, lineHeight = 17.sp,
                            )
                            T("导入后请检查参数，自动指令默认关闭。", 11.sp, color = C.Bad)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton("取消", Modifier.weight(1f), onClick = onDismiss)
                DialogButton(
                    if (confirmBusy) "正在恢复…" else if (isBackup) "确认恢复" else "确认导入",
                    Modifier.weight(1f), primary = true,
                    onClick = { if (!confirmBusy) onConfirm() },
                )
            }
        }
    }
}

@Composable
private fun previewLine(label: String, values: List<String>) {
    if (values.isEmpty()) return
    T(
        "$label：${values.take(6).joinToString("、")}" +
            if (values.size > 6) " 等 ${values.size} 项" else "",
        12.sp, color = C.OnSurfaceVar, lineHeight = 17.sp,
    )
}

@Composable
private fun DialogButton(
    title: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (primary) C.Primary else C.Tonal
    val foreground = if (primary) C.OnPrimary else C.OnSurface
    Column(
        modifier.clip(Capsule()).background(background).pressable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) { T(title, 14.sp, FontWeight.SemiBold, foreground) }
}
