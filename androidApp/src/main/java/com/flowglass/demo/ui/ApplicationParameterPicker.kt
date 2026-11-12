package com.flowglass.demo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.flowglass.demo.system.SysTick
import com.flowglass.demo.system.exec.LaunchableApp
import com.flowglass.demo.system.exec.LaunchableApps
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.CancellationException

@Composable
internal fun ApplicationParameterPicker(item: CommandItem, onParameter: (String) -> Unit) {
    val context = LocalContext.current
    val tick = SysTick.tick
    var query by rememberSaveable(item.id) { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(context, tick) {
        failed = false
        try { apps = LaunchableApps.load(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    T("搜索应用名称或包名", 14.sp, FontWeight.Medium)
    ParameterTextField(query, "app-search") { query = it }
    T("点选应用后保存包名；返回不会清空原选择。", 12.sp, color = C.OnSurfaceVar)
    T("当前包名：${item.parameter.ifEmpty { "未选择" }}", 13.sp, color = C.OnSurfaceVar)
    val filtered = apps?.filter { it.label.contains(query.trim(), true) || it.packageName.contains(query.trim(), true) }
    when {
        failed -> T("读取应用列表失败，请重新打开后重试。", 13.sp, color = C.Bad)
        filtered == null -> T("正在读取应用…", 13.sp, color = C.Muted)
        filtered.isEmpty() -> T("未找到匹配应用", 13.sp, color = C.Muted)
        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 224.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(filtered, key = { _, app -> app.packageName }) { index, app ->
                Row(Modifier.fillMaxWidth().staggerIn(index.coerceAtMost(8)).clip(RoundedRectangle(24.dp))
                    .background(if (item.parameter == app.packageName) C.PrimarySoft else C.Card)
                    .semantics { selected = item.parameter == app.packageName }
                    .editorAction("选择应用：${app.label}") { onParameter(app.packageName) }
                    .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val icon = remember(app.icon) { app.icon.toBitmap(96, 96).asImageBitmap() }
                    Image(icon, "应用图标：${app.label}", Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        T(app.label, 15.sp, FontWeight.Medium)
                        T(app.packageName, 12.sp, color = C.OnSurfaceVar)
                    }
                    if (item.parameter == app.packageName) T("已选", 12.sp, color = C.Primary)
                }
            }
        }
    }
}

@Composable
internal fun itemParameterLabel(section: CommandSection, item: CommandItem): String {
    val context = LocalContext.current
    val tick = SysTick.tick
    var label by remember(item.parameter) { mutableStateOf(item.parameter) }
    LaunchedEffect(context, tick, section, item.name, item.parameter) {
        label = if (section == CommandSection.ACTIONS && item.name == "启动应用") {
            try { LaunchableApps.label(context, item.parameter) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { item.parameter }
        } else item.parameter
    }
    return label.ifEmpty { "点按设置参数" }
}
