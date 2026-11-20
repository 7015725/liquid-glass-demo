package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.demo.system.RootInfo
import com.flowglass.demo.system.exec.RootTasks
import com.flowglass.demo.system.exec.ScriptTask
import com.flowglass.demo.system.exec.StepStatus
import com.flowglass.demo.system.exec.TaskOutcome
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ParamSpec
import com.kyant.shapes.RoundedRectangle
import io.ma7moud3ly.nemo.NemoCodeEditor
import io.ma7moud3ly.nemo.model.CodeState
import io.ma7moud3ly.nemo.model.EditorSettings
import io.ma7moud3ly.nemo.model.EditorTheme
import io.ma7moud3ly.nemo.model.EditorThemes
import io.ma7moud3ly.nemo.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal val CodeActionIds = setOf("action.script", "action.root_shell")

private fun codeKeyOf(typeId: String?): String = if (typeId == "action.root_shell") "command" else "script"

private fun codeLanguageOf(typeId: String?): Language =
    if (typeId == "action.root_shell") Language.SHELL else Language.JAVASCRIPT

/**
 * Dedicated, full-height editor for script actions. Scripts do not fit the shared parameter form:
 * they need the whole page, line numbers and a keyboard that never covers the cursor, while the
 * timeout stays one tap away.
 */
@Composable
internal fun ScriptActionEditorPage(
    item: CommandItem,
    meta: ComponentMeta,
    cmd: Cmd,
    root: RootInfo?,
    variables: List<VariableOption>,
    hasOutputs: Boolean,
    onParameter: (String) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onOutputs: () -> Unit,
    onMove: () -> Unit,
) {
    var fields by remember(item.id) { mutableStateOf(ParameterForm.fields(meta, item.parameter)) }
    var variableMenu by remember { mutableStateOf(false) }
    var timeoutOpen by remember { mutableStateOf(false) }
    val timeoutSpec: ParamSpec? = meta.params.firstOrNull { it.key == "timeout" }

    fun update(key: String, value: String) {
        val next = fields + (key to value)
        fields = next
        ParameterForm.compose(meta, next).onSuccess(onParameter)
    }

    val codeKey = codeKeyOf(meta.typeId)
    val language = codeLanguageOf(meta.typeId)
    val script = fields[codeKey].orEmpty()
    val hint = meta.params.firstOrNull { it.key == codeKey }?.hint.orEmpty()
    val timeout = fields["timeout"].orEmpty()
    val code = remember(item.id) { CodeState(initialCode = script, language = language) }
    LaunchedEffect(script) {
        if (code.code != script) code.updateText(script)
    }
    LaunchedEffect(code) {
        snapshotFlow { code.code }.collect { text -> if (text != fields[codeKey]) update(codeKey, text) }
    }
    // Nemo grabs focus on its first composition; the page should open without a keyboard.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        // Nemo requests focus a few frames into the enter animation, so one early clear is not
        // enough; keep dropping it briefly instead of letting the keyboard cover the page.
        repeat(3) {
            delay(150)
            focusManager.clearFocus(force = true)
        }
    }

    // A trial run uses the text on screen, not the last saved parameter, so typing is never lost.
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var runResult by remember(item.id) { mutableStateOf<RunInfo?>(null) }
    var copied by remember(item.id) { mutableStateOf(false) }
    fun runNow() {
        if (running) return
        val parameter = ParameterForm.compose(meta, fields + (codeKey to code.code)).getOrElse { error ->
            runResult = RunInfo(false, "参数无效：${error.message?.take(200)}", 0)
            return
        }
        val probe = item.copy(parameter = parameter)
        running = true
        runResult = null
        copied = false
        scope.launch {
            val started = System.nanoTime()
            val outcome = runCatching {
                if (language == Language.SHELL) {
                    val authorized = root ?: error("需要 Root 授权，请先在设置里授权 Root")
                    RootTasks.run(appContext, cmd, probe, authorized)
                } else {
                    runInterruptible(Dispatchers.Default) { ScriptTask.run(appContext, cmd, probe, null) }
                }
            }.getOrElse { error ->
                TaskOutcome(StepStatus.FAILURE, error.message?.take(400) ?: "执行失败")
            }
            runResult = RunInfo(
                ok = outcome.status == StepStatus.SUCCESS,
                detail = outcome.detail,
                millis = (System.nanoTime() - started) / 1_000_000,
            )
            running = false
        }
    }

    val theme = remember(C.dark) { scriptEditorTheme() }
    val settings = remember(theme) {
        EditorSettings(
            theme = theme,
            fontSize = 14,
            showLineNumbers = true,
            showIndentGuides = true,
            enableAutocomplete = false,
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }

    Column(
        Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        DetailTopBar(
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
            title = { T(item.name, 20.sp, FontWeight.Bold, maxLines = 1) },
            subtitle = "脚本 · ${cmd.title}",
            trailing = {
                TopBarIconButton(Ic.UpDown, "移动到其他位置", onClick = onMove)
                TopBarTextButton(if (running) "运行中…" else "运行", onClick = { if (!running) runNow() })
                TopBarTextButton("完成", onClick = onDone)
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(if (language == Language.SHELL) "Shell" else "JavaScript", 12.sp, color = C.Muted)
            Spacer(Modifier.weight(1f))
            T("${code.code.lineSequence().count()} 行", 12.sp, color = C.Muted)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedRectangle(22.dp)).background(C.Card),
        ) {
            NemoCodeEditor(
                state = code,
                modifier = Modifier.fillMaxSize().testTag("script-editor").imeAutoScroll(),
                settings = settings,
            )
        }
        runResult?.let { info ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp)
                    .clip(RoundedRectangle(16.dp))
                    .background(if (info.ok) C.GoodSoft else C.BadSoft)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(
                        if (info.ok) "运行成功" else "运行失败", 13.sp, FontWeight.SemiBold,
                        color = if (info.ok) C.GoodText else C.Bad,
                    )
                    Spacer(Modifier.weight(1f))
                    T("${info.millis} ms", 11.sp, color = C.Muted)
                    Box(
                        Modifier.padding(start = 10.dp).heightIn(min = 28.dp)
                            .clip(RoundedRectangle(9.dp))
                            .background(C.Card.copy(alpha = 0.7f))
                            .editorAction("复制运行结果") {
                                val text = info.detail.ifBlank { "没有输出" }
                                val manager = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                manager.setPrimaryClip(android.content.ClipData.newPlainText("脚本运行结果", text))
                                android.widget.Toast.makeText(appContext, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                copied = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        T(if (copied) "已复制" else "复制", 11.sp, FontWeight.Medium, C.Primary)
                    }
                }
                T(
                    info.detail.ifBlank { "没有输出" }, 12.sp, color = C.OnSurfaceVar, lineHeight = 17.sp,
                    modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
        if (hint.isNotBlank()) {
            T(
                hint, 11.sp, color = C.Muted, maxLines = 2,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (variables.isNotEmpty()) {
                EditorPill("插入变量", "插入变量") { variableMenu = true }
            }
            if (hasOutputs) {
                EditorPill("输出", "配置输出变量", action = onOutputs)
            }
            Spacer(Modifier.weight(1f))
            if (timeoutSpec != null) {
                EditorPill(
                    if (timeout.isBlank()) "超时" else "超时 ${timeout}s",
                    "设置超时",
                ) { timeoutOpen = true }
            }
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    if (variableMenu) {
        VariableListDialog(variables, onDismiss = { variableMenu = false }) { option ->
            val text = code.code
            val caret = code.cursorPosition.coerceIn(0, text.length)
            val snippet = "{" + option.name + "}"
            code.updateText(text.substring(0, caret) + snippet + text.substring(caret), caret + snippet.length)
            variableMenu = false
        }
    }

    if (timeoutOpen && timeoutSpec != null) {
        Dialog(
            onDismissRequest = { timeoutOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp).clip(RoundedRectangle(24.dp))
                    .background(C.Card).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("超时", 16.sp, FontWeight.SemiBold)
                T("脚本超过这个时间会被中断，并把这个动作标记为失败。", 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
                ParamField(
                    spec = timeoutSpec,
                    current = fields["timeout"].orEmpty(),
                    tag = "script-timeout",
                    onValue = { value -> update("timeout", value) },
                )
                EditorPill("完成", "关闭超时设置") { timeoutOpen = false }
            }
        }
    }
}

private data class RunInfo(val ok: Boolean, val detail: String, val millis: Long)

/** Editor palette that follows the app theme; the container supplies the glass. */
internal fun scriptEditorTheme(): EditorTheme {
    val base = if (C.dark) EditorThemes.VS_CODE_DARK else EditorThemes.GITHUB_LIGHT
    return base.copy(
        background = 0x00000000,
        gutter = 0x00000000,
        lineNumber = C.Muted.toArgbLong(),
        lineNumberActive = C.Primary.toArgbLong(),
        currentLineBackground = if (C.dark) 0x12FFFFFF else 0x0A000000,
        selection = C.Primary.copy(alpha = 0.32f).toArgbLong(),
    )
}

private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
