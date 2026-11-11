package com.flowglass.demo.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.flowglass.demo.ui.editor.EditorField
import com.flowglass.demo.ui.editor.EditorFrame
import com.flowglass.demo.ui.editor.EditorCard
import com.flowglass.engine.RuleCodec
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded UTF-8 read before parsing; document-provider files can have unknown or false sizes. */
internal fun readRuleDocument(input: InputStream): String {
    val limit = 1_048_576
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "JSON 文件不能超过 1 MiB" }
        output.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
}

@Composable
fun RuleFileTransfers(state: AppState) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireNotNull(resolver.openInputStream(uri)) { "无法打开文件" }.use(::readRuleDocument)
                }
                state.previewImport(text)
            } catch (e: Exception) { state.message = "无法导入：${e.message}" }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val payload = state.exportPayload
        if (payload == null) {
            state.message = "导出失败：导出快照已丢失，请重新导出。所选文件可能为空。"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入文件" }.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    val actual = requireNotNull(resolver.openInputStream(uri)) { "写入后无法回读核验" }.use(::readRuleDocument)
                    check(actual == payload) { "写入后回读内容不一致，请重新导出" }
                }
                state.message = "JSON 已导出并回读核验（含全局变量值）"
            } catch (e: Exception) { state.message = "导出未通过核验：${e.message}" }
            finally { state.exportPayload = null }
        }
    }
    LaunchedEffect(state.importRequested) {
        if (state.importRequested > 0) {
            state.importRequested = 0
            importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }
    LaunchedEffect(state.exportRequested) {
        if (state.exportRequested > 0) {
            state.exportRequested = 0
            exporter.launch("流光指令.json")
        }
    }
}

@Composable
fun AuthoringSearch(state: AppState) {
    if (state.showSearch) {
        EditorField("搜索名称或描述", state.query, { state.query = it }, "rule-search")
        AuthoringButton("清空并收起", "search-close", primary = false) { state.query = ""; state.showSearch = false }
    }
}

@Composable
fun ImportReviewScreen(state: AppState) {
    val bundle = state.importPreview ?: return
    val scripts = remember(bundle) { RuleCodec.scriptWarnings(bundle) }
    val cancel = { state.importPreview = null; state.importScriptConfirmed = false }
    BackHandler(onBack = cancel)
    EditorFrame("导入预览", cancel, "确认导入", "import-commit", state::commitImport) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EditorCard("即将导入 ${bundle.rules.size} 条指令") {
                T("所有导入指令重新分配 ID 并保持停用；不执行任何脚本。全局变量重名时拒绝整批导入，不覆盖现有数据。", 13.sp, color = C.OnSurfaceVar)
                bundle.rules.forEach { T("• ${it.name} · ${if (it.type == "auto") "自动" else "一键"} · ${it.actions.size} 个动作", 14.sp) }
                T("全局变量：${bundle.globals.joinToString { it.name }.ifBlank { "无" }}", 13.sp)
            }
            if (scripts.isNotEmpty()) EditorCard("脚本与表达式 · 需要额外确认") {
                T("这些内容可能在未来接入执行端后获得系统或 Root 能力。请逐项查看完整脚本；本次导入不会运行。", 13.sp, color = C.Bad)
                scripts.forEach { T(it, 13.sp, lineHeight = 19.sp) }
                AuthoringButton(if (state.importScriptConfirmed) "已确认全部脚本 · 点击撤回" else "我已查看并确认全部脚本", "import-script-confirm", primary = !state.importScriptConfirmed) {
                    state.importScriptConfirmed = !state.importScriptConfirmed
                }
            }
            EditorCard("完整 JSON") {
                T(RuleCodec.exportBundle(bundle.rules, bundle.globals), 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
fun AuthoringFeedback(state: AppState) {
    if (!state.busy && state.message == null) return
    Dialog(onDismissRequest = { if (!state.busy) state.message = null }) {
        Column(Modifier.fillMaxWidth().background(C.Card, RoundedRectangle(26.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T(if (state.busy) "正在保存" else "提示", 20.sp, FontWeight.Bold)
            T(if (state.busy) "正在写入本机数据库…" else state.message.orEmpty(), 14.sp,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), lineHeight = 21.sp)
            if (!state.busy) AuthoringButton("知道了", "feedback-dismiss", Modifier.fillMaxWidth()) { state.message = null }
        }
    }
}
