package com.flowglass.demo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.exec.ApplicationCatalog
import com.flowglass.demo.system.exec.ApplicationFilter
import com.flowglass.demo.system.exec.LaunchableApp
import com.flowglass.demo.system.exec.applicationsForScope
import com.flowglass.demo.system.exec.filterApplications
import com.flowglass.demo.system.exec.groupApplications
import com.flowglass.demo.system.exec.isSelectedApplication
import com.flowglass.demo.system.exec.profileName
import com.flowglass.demo.system.exec.shouldAutoScanRoot
import com.flowglass.engine.ApplicationScope
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Full-screen, reusable application picker. It is independent of any component id: the caller
 * supplies the current value and the profile scope declared by the ParamSpec.
 */
@Composable
internal fun ApplicationListPage(
    current: String,
    scope: ApplicationScope,
    allowEmpty: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var normalApps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var normalError by remember { mutableStateOf<String?>(null) }
    var rootApps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var rootLoading by remember { mutableStateOf(false) }
    var rootError by remember { mutableStateOf<String?>(null) }
    var rootGranted by remember { mutableStateOf(false) }
    var rootAttempted by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var manualOpen by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf(current.takeUnless { it.isBlank() }.orEmpty()) }
    var manualError by remember { mutableStateOf<String?>(null) }

    fun applyRootResult(result: Result<List<LaunchableApp>>) {
        rootLoading = false
        result.onSuccess {
            rootApps = it
            rootError = null
            rootGranted = true
        }.onFailure {
            if (it is CancellationException) throw it
            rootError = it.message ?: "Root 扫描失败"
        }
    }

    fun scanRoot(requestPermission: Boolean, force: Boolean) {
        if (rootLoading) return
        rootLoading = true
        rootError = null
        rootAttempted = true
        coroutineScope.launch {
            applyRootResult(ApplicationCatalog.root(context, requestPermission, force))
        }
    }

    LaunchedEffect(Unit) {
        val normal = ApplicationCatalog.normal(context)
        normal.onSuccess { normalApps = it }
            .onFailure {
                if (it is CancellationException) throw it
                normalApps = emptyList()
                normalError = it.message ?: "读取应用列表失败"
            }

        if (scope == ApplicationScope.ALL_PROFILES && Root.findSu() != null) {
            val granted = ApplicationCatalog.hasRootGrant()
            rootGranted = granted
            if (shouldAutoScanRoot(scope, rootAvailable = true, rememberedGrant = granted)) {
                // Automatic path: no requestPermission, no manager prompt, one attempt.
                rootLoading = true
                rootAttempted = true
                applyRootResult(ApplicationCatalog.root(context, requestPermission = false))
            }
        }
    }

    val merged = remember(normalApps, rootApps) {
        (normalApps.orEmpty() + rootApps).distinctBy { it.id }
    }
    val scoped = remember(merged, scope) { applicationsForScope(merged, scope) }
    val filterIndexShown = filterIndex.coerceIn(
        0,
        if (scope == ApplicationScope.CURRENT_PROFILE) 1 else ApplicationFilter.entries.lastIndex,
    )
    val filter = if (scope == ApplicationScope.CURRENT_PROFILE && filterIndexShown == 1) {
        ApplicationFilter.MAIN
    } else {
        ApplicationFilter.entries[filterIndexShown]
    }
    val filtered = remember(scoped, query, filter) {
        filterApplications(scoped, query, filter)
    }
    val groups = remember(filtered) { groupApplications(filtered) }

    Column(
        Modifier.fillMaxSize().background(C.Bg).testTag("application-list-page")
            .statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailTopBar(
            onBack = onDismiss,
            title = { T("选择应用", 20.sp, FontWeight.Bold, maxLines = 1) },
            subtitle = "主应用和分身分别显示",
        )

        ApplicationSearchField(query, onQueryChange = { query = it })

        FilterChips(
            if (scope == ApplicationScope.CURRENT_PROFILE) listOf("全部", "主应用")
            else listOf("全部", "主应用", "分身"),
            filterIndex.coerceIn(
                0,
                if (scope == ApplicationScope.CURRENT_PROFILE) 1 else ApplicationFilter.entries.lastIndex,
            ),
            onSelect = { filterIndex = it },
            modifier = Modifier.testTag("application-filter"),
        )

        if (scope == ApplicationScope.CURRENT_PROFILE) {
            T(
                "应用前台触发器只能识别当前资料的应用；分身应用不会列在这里。",
                12.sp,
                color = C.OnSurfaceVar,
                lineHeight = 18.sp,
            )
        } else if (Root.findSu() != null) {
            RootScanStatus(
                apps = rootApps,
                loading = rootLoading,
                error = rootError,
                attempted = rootAttempted,
                granted = rootGranted,
                onScan = { scanRoot(requestPermission = true, force = true) },
            )
        }

        when {
            normalApps == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                T("正在读取应用…", 14.sp, color = C.Muted)
            }
            normalError != null && merged.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center,
            ) {
                T("读取应用失败：$normalError", 13.sp, color = C.Bad, lineHeight = 19.sp)
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T(if (query.isBlank()) "没有可选应用" else "未找到匹配应用", 14.sp, color = C.Muted)
                    if (query.isNotBlank()) {
                        T("可换一个关键词，或手动输入包名。", 12.sp, color = C.Muted)
                    }
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxWidth().weight(1f).testTag("application-results"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEach { group ->
                    item(key = "header-${group.title}") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T(group.title, 13.sp, FontWeight.SemiBold, color = C.OnSurfaceVar,
                                modifier = Modifier.weight(1f))
                            T("${group.apps.size}", 12.sp, color = C.Muted, tabular = true)
                        }
                    }
                    items(group.apps, key = { it.id }) { app ->
                        ApplicationRow(
                            app = app,
                            selected = isSelectedApplication(app, current),
                            onClick = { onPick(app.id) },
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorPill("手动输入", "手动输入包名或 package@userId",
                modifier = Modifier.testTag("application-manual-input")) {
                manualText = current
                manualError = null
                manualOpen = true
            }
            if (allowEmpty) {
                EditorPill("留空", "留空表示匹配任意应用") { onPick("") }
            }
        }
    }

    if (manualOpen) {
        ManualApplicationDialog(
            value = manualText,
            error = manualError,
            scope = scope,
            onValueChange = { manualText = it; manualError = null },
            onDismiss = { manualOpen = false },
            onConfirm = {
                ApplicationCatalog.normalizeTarget(manualText, scope)
                    .onSuccess { value ->
                        manualOpen = false
                        onPick(value)
                    }
                    .onFailure { manualError = it.message ?: "包名格式不正确" }
            },
        )
    }
}

@Composable
private fun ApplicationSearchField(value: String, onQueryChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("application-search")
            .clip(RoundedRectangle(22.dp)).background(C.Card).padding(horizontal = 16.dp, vertical = 14.dp),
        singleLine = true,
        textStyle = TextStyle(color = C.OnSurface, fontSize = 16.sp, lineHeight = 22.sp),
        cursorBrush = SolidColor(C.Primary),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) T("搜索应用名称、包名或用户", 14.sp, color = C.Muted)
                inner()
            }
        },
    )
}

@Composable
private fun RootScanStatus(
    apps: List<LaunchableApp>,
    loading: Boolean,
    error: String?,
    attempted: Boolean,
    granted: Boolean,
    onScan: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            loading -> T("正在扫描分身应用…", 12.sp, color = C.OnSurfaceVar)
            error != null -> {
                T(error, 12.sp, color = C.Bad, lineHeight = 18.sp)
                EditorPill("重试 Root 扫描", "重试 Root 扫描", modifier = Modifier.testTag("application-root-scan")) {
                    onScan()
                }
            }
            apps.isNotEmpty() -> {
                val clones = apps.count { it.userId != 0 }
                T("Root 扫描完成：共 ${apps.size} 个可启动应用，其中 $clones 个分身/其他资料。", 12.sp,
                    color = C.OnSurfaceVar, lineHeight = 18.sp)
            }
            attempted && granted -> T("Root 扫描没有返回可启动应用。", 12.sp, color = C.OnSurfaceVar)
            else -> {
                EditorPill("Root 扫描分身应用", "Root 扫描分身应用",
                    modifier = Modifier.testTag("application-root-scan")) { onScan() }
                T("首次扫描需要 Root 授权；之后进入本页会自动扫描。", 12.sp, color = C.Muted,
                    lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun ApplicationRow(app: LaunchableApp, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedRectangle(22.dp))
            .background(if (selected) C.PrimarySoft else C.Card)
            .semantics { this.selected = selected }
            .editorAction("选择应用：${app.label}") { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = remember(app.icon) { app.icon.toBitmap(96, 96).asImageBitmap() }
        Image(icon, "应用图标：${app.label}", Modifier.width(40.dp).height(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(app.label, 15.sp, FontWeight.Medium, maxLines = 1)
            T(
                if (app.userId == 0) app.id else "${app.id} · ${profileName(app)}",
                12.sp,
                color = C.OnSurfaceVar,
                maxLines = 1,
            )
        }
        if (app.userId != 0) {
            T("分身", 11.sp, color = C.Primary, modifier = Modifier.padding(start = 8.dp))
        }
        if (selected) {
            T("已选", 11.sp, color = C.Primary, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ManualApplicationDialog(
    value: String,
    error: String?,
    scope: ApplicationScope,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(C.ScrimSubtle).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedRectangle(28.dp)).background(C.Card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("手动输入应用", 18.sp, FontWeight.Bold)
                T(
                    if (scope == ApplicationScope.CURRENT_PROFILE) {
                        "输入当前资料的应用包名。"
                    } else {
                        "主应用输入包名；分身输入 package@userId。"
                    },
                    12.sp,
                    color = C.OnSurfaceVar,
                    lineHeight = 18.sp,
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("application-manual-field")
                        .clip(RoundedRectangle(20.dp)).background(C.Tonal).padding(14.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = C.OnSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(C.Primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                error?.let { T(it, 12.sp, color = C.Bad) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditorPill("取消", "取消手动输入", modifier = Modifier.weight(1f), action = onDismiss)
                    EditorPill("确定", "确定手动输入", modifier = Modifier.weight(1f), action = onConfirm)
                }
            }
        }
    }
}
