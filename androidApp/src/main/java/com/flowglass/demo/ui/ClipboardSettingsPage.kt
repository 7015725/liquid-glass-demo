package com.flowglass.demo.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.demo.system.Grant
import com.flowglass.demo.system.Perms
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.system.clipboard.ClipboardArchive
import com.flowglass.demo.system.clipboard.ClipboardHistoryStore
import com.flowglass.demo.system.clipboard.ClipboardOverlay
import com.flowglass.demo.system.clipboard.ClipboardSettings
import com.flowglass.demo.xposed.ModuleStatus

@Composable
internal fun ClipboardSettingsPage() {
    val ctx = LocalContext.current
    val settings = remember { ClipboardSettings(ctx) }
    val store = remember { ClipboardHistoryStore(ctx) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var retention by remember { mutableStateOf(settings.retentionDays) }
    var count by remember { mutableStateOf(0) }
    var clearArmed by remember { mutableStateOf(false) }

    DisposableEffect(store) {
        onDispose { store.close() }
    }
    LaunchedEffect(enabled, retention) {
        withContext(Dispatchers.IO) {
            store.trim(
                retentionDays = retention,
                maxEntries = settings.maxEntries,
            )
        }
        count = withContext(Dispatchers.IO) { store.count() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            count = withContext(Dispatchers.IO) { runCatching { store.count() }.getOrDefault(count) }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupTitle("记录", 0)
        GroupCard(1) { bd ->
            ListRow(
                Row1(
                    Ic.Copy,
                    "记录剪贴板变化",
                    value = if (enabled) "开启" else "关闭",
                    sub = "全局监听需要 LSPosed 系统钩子；无钩子时只在应用可见时记录",
                    valueColor = if (enabled) C.GoodText else C.Muted,
                ),
                true,
            ) {
                LiquidToggle({ enabled }, {
                    enabled = it
                    settings.enabled = it
                    ClipboardArchive.sync(ctx)
                }, bd)
            }
        }

        GroupTitle("存储", 2)
        GroupCard(3) {
            ListRow(
                Row1(
                    Ic.Timer,
                    "保留天数",
                    "$retention 天",
                    sub = "超过保留期的记录会在启动和写入时清理",
                ),
                true,
            ) {
                PrimaryButton("切换", Ic.UpDown, {
                    val options = listOf(7, 14, 30, 90)
                    retention = options[(options.indexOf(retention) + 1).mod(options.size)]
                    settings.retentionDays = retention
                })
            }
            ListRow(
                Row1(
                    Ic.Archive,
                    "记录条数",
                    "$count / ${settings.maxEntries}",
                    sub = "最多保留 ${settings.maxEntries} 条，旧的自动删除",
                ),
                true,
            )
            ListRow(
                Row1(
                    Ic.Trash,
                    if (clearArmed) "再次确认清空" else "清空全部记录",
                    sub = "不可恢复；不会影响指令和日志",
                ),
                false,
            ) {
                PrimaryButton(if (clearArmed) "确认" else "清空", Ic.Trash, {
                    if (clearArmed) {
                        store.clear()
                        count = 0
                        clearArmed = false
                    } else {
                        clearArmed = true
                    }
                })
            }
        }

        GroupTitle("显示", 4)
        GroupCard(5) {
            val overlayGranted = Settings.canDrawOverlays(ctx)
            ListRow(
                Row1(
                    Ic.Grid,
                    "悬浮窗权限",
                    value = if (overlayGranted) "已授予" else "未授予",
                    sub = if (overlayGranted) {
                        "全局剪贴板动作可以在其他应用上显示浮窗"
                    } else {
                        "全局剪贴板动作需要在其他应用上显示浮窗 · 点击授权"
                    },
                    valueColor = if (overlayGranted) C.GoodText else C.Muted,
                    onClick = if (overlayGranted) null else ({
                        Perms.all.firstOrNull { it.key == "overlay" }?.let { item ->
                            when (val grant = item.grant) {
                                is Grant.Page -> Perms.open(ctx, grant.intent(ctx))
                                is Grant.Runtime -> android.widget.Toast.makeText(
                                    ctx,
                                    "请在系统设置中授予悬浮窗",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }),
                ),
                true,
            )
            val lsposedReady = SystemHookSettings.isAlive(ctx)
            ListRow(
                Row1(
                    Ic.Puzzle,
                    "LSPosed 系统钩子",
                    value = if (lsposedReady) "已就绪" else "未就绪",
                    sub = "全局后台记录依赖系统钩子 · 点击打开管理器",
                    valueColor = if (lsposedReady) C.GoodText else C.Muted,
                    onClick = {
                        val manager = ModuleStatus.managerPackage(ctx)
                        val intent = manager?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
                        if (intent != null) Perms.open(ctx, intent)
                        else android.widget.Toast.makeText(
                            ctx,
                            "未找到 LSPosed / Vector 管理器",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                ),
                true,
            )
            ListRow(
                Row1(
                    Ic.Copy,
                    "预览浮窗",
                    sub = "立即打开浮窗，检查布局与搜索",
                ),
                false,
            ) {
                PrimaryButton("打开浮窗", Ic.Copy, {
                    if (!ClipboardOverlay.show(ctx)) {
                        android.widget.Toast.makeText(
                            ctx,
                            "请先授予悬浮窗权限",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                })
            }
        }
        NoteCard(
            6,
            "剪贴板历史是敏感数据：只保存在应用私有数据库，不导出、不参与云备份；日志中的剪贴板正文会被脱敏。",
        )
    }
}
