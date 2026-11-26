package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flowglass.demo.system.a11y.A11yServiceCatalog
import com.flowglass.demo.system.a11y.A11yServiceEntry
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → 无障碍管理. The scrolling container and horizontal padding come from PageColumn in
 * App.kt; this page only emits its sections, exactly like the other settings pages.
 */
@Composable
internal fun A11yManagerPage() {
    val ctx = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<A11yServiceEntry>>(emptyList()) }
    var master by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        entries = withContext(Dispatchers.IO) { A11yServiceCatalog.load(ctx, ctx.packageName) }
        master = A11yServiceCatalog.masterEnabled(ctx)
    }

    var k = 0
    StatusCard(
        k++, Ic.Accessibility, "无障碍服务",
        if (master) "已开启" else "已关闭", master,
        "${entries.size}", "个服务",
        listOf("Android ${android.os.Build.VERSION.RELEASE}"),
        action = {
            PrimaryButton("刷新列表", Ic.Refresh, { revision++ }, Modifier.fillMaxWidth())
        },
    )
    GroupTitle("已安装的无障碍服务", k++)
    GroupCard(k++) {
        if (entries.isEmpty()) {
            ListRow(Row1(Ic.Info, "没有找到无障碍服务", sub = "请先在系统设置里开启至少一个无障碍服务"), false)
        }
        entries.forEachIndexed { index, entry ->
            ListRow(
                Row1(
                    Ic.Accessibility,
                    if (entry.isSelf) "流光自动化" else entry.appLabel,
                    sub = "${entry.serviceLabel} · ${entry.packageName}",
                    value = if (entry.enabled) "已启用" else "未启用",
                    valueColor = if (entry.enabled) C.GoodText else C.Muted,
                ),
                divider = index < entries.lastIndex,
            )
        }
    }
    NoteCard(
        k,
        "只读取系统已安装的无障碍服务，不需要额外权限。默认不对任何服务做保活。",
    )
}

/** Square tick used by the checkbox rows; the project has no Material3 dependency. */
@Composable
internal fun SelectionMark(selected: Boolean) {
    Box(
        Modifier.size(24.dp).clip(RoundedRectangle(8.dp))
            .background(if (selected) C.Primary else C.Tonal),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) LineIcon(Ic.Check, C.OnPrimary, 16.dp)
    }
}
