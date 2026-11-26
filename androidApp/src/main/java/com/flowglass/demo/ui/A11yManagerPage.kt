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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.a11y.A11yKeepAliveSettings
import com.flowglass.demo.system.a11y.A11yKsuModule
import com.flowglass.demo.system.a11y.productionRunner
import com.flowglass.demo.system.a11y.A11yRowModel
import com.flowglass.demo.system.a11y.A11yServiceCatalog
import com.flowglass.demo.system.a11y.A11yServiceEntry
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    var enabled by remember { mutableStateOf(A11yKeepAliveSettings.isEnabled(ctx)) }
    var targets by remember { mutableStateOf(A11yKeepAliveSettings.targets(ctx)) }
    var statuses by remember { mutableStateOf(A11yKeepAliveSettings.statuses(ctx)) }
    var backend by remember { mutableStateOf(A11yKeepAliveSettings.backendLabel(ctx)) }
    var ksu by remember { mutableStateOf(A11yKeepAliveSettings.isKsuInstalled(ctx)) }
    var rootNote by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun syncRootConfig() {
        if (!ksu) return
        val info = Root.check(request = false)
        if (!info.granted) return
        A11yKsuModule.refresh(productionRunner(info), A11yKeepAliveSettings.targets(ctx))
    }

    LaunchedEffect(revision) {
        entries = withContext(Dispatchers.IO) { A11yServiceCatalog.load(ctx, ctx.packageName) }
        master = A11yServiceCatalog.masterEnabled(ctx)
        enabled = A11yKeepAliveSettings.isEnabled(ctx)
        targets = A11yKeepAliveSettings.targets(ctx)
    }
    // The page polls while it is composed: the hook reports asynchronously and there is no push
    // channel into Compose. Leaving the page cancels the loop.
    LaunchedEffect(Unit) {
        A11yKeepAliveSettings.broadcastConfig(ctx)
        while (true) {
            delay(2_000)
            targets = A11yKeepAliveSettings.targets(ctx)
            statuses = A11yKeepAliveSettings.statuses(ctx)
            backend = A11yKeepAliveSettings.backendLabel(ctx)
        }
    }

    val rows = remember(entries, targets, statuses) { A11yRowModel.build(entries, targets, statuses) }
    var k = 0
    StatusCard(
        k++, Ic.Accessibility, "无障碍保活",
        backend, backend != "未启用",
        "${targets.size}", "个服务已选",
        listOf(
            if (master) "系统无障碍已开启" else "系统无障碍已关闭",
            when {
                A11yKeepAliveSettings.isHookLoaded(ctx) && !A11yKeepAliveSettings.isHookSupported(ctx) ->
                    "钩子已加载，但本 ROM 找不到连接点"
                A11yKeepAliveSettings.isHookLoaded(ctx) ->
                    "钩子已加载 · ${A11yKeepAliveSettings.hookCount(ctx).coerceAtLeast(0)} 个检测点"
                else -> "钩子未加载"
            },
        ),
        action = {
            PrimaryButton("刷新列表", Ic.Refresh, { revision++ }, Modifier.fillMaxWidth())
        },
    )
    GroupTitle("总开关", k++)
    GroupCard(k++) { bd ->
        ListRow(
            Row1(
                Ic.Shield,
                "启用无障碍保活",
                if (enabled) "已开启" else "默认关闭",
                sub = "只在你仍开启系统无障碍时恢复断开的绑定",
            ),
            true,
        ) {
            LiquidToggle({ enabled }, { on ->
                enabled = on
                A11yKeepAliveSettings.setEnabled(ctx, on)
                targets = A11yKeepAliveSettings.targets(ctx)
                scope.launch { runCatching { syncRootConfig() } }
            }, bd)
        }
    }
    GroupTitle("已安装的无障碍服务", k++)
    GroupCard(k++) {
        if (rows.isEmpty()) {
            ListRow(Row1(Ic.Info, "没有找到无障碍服务", sub = "请先在系统设置里开启至少一个无障碍服务"), false)
        }
        rows.forEachIndexed { index, row ->
            ListRow(
                Row1(
                    Ic.Accessibility,
                    row.title,
                    sub = row.subtitle,
                    value = row.stateText,
                    valueColor = if (row.ok) C.GoodText else C.Muted,
                    onClick = {
                        // Toggle first: when the switch is still off, seeding the self target in
                        // setEnabled() would cancel the tap for the self row (seed + toggle).
                        A11yKeepAliveSettings.toggleTarget(ctx, row.component)
                        if (!enabled) {
                            enabled = true
                            A11yKeepAliveSettings.setEnabled(ctx, true, seed = false)
                        }
                        targets = A11yKeepAliveSettings.targets(ctx)
                        scope.launch { runCatching { syncRootConfig() } }
                    },
                ),
                divider = index < rows.lastIndex,
            ) { SelectionMark(row.selected) }
        }
    }
    GroupTitle("Root 模块（KernelSU / Magisk）", k++)
    GroupCard(k++) { bd ->
        ListRow(
            Row1(
                Ic.Shield,
                if (ksu) "Root 模块已安装" else "Root 模块未安装",
                sub = rootNote.ifEmpty {
                    "LSPosed 钩子不可用时的兜底：service.sh 每 30 秒巡检一次"
                },
                value = if (ksu) "已安装" else "未安装",
                valueColor = if (ksu) C.GoodText else C.Muted,
            ),
            false,
        )
    }
    GroupCard(k++) {
        PrimaryButton(
            when {
                busy -> "处理中…"
                ksu -> "卸载 Root 模块"
                else -> "安装 Root 模块"
            },
            Ic.Shield,
            {
                if (busy) return@PrimaryButton
                busy = true
                rootNote = "正在获取 Root…"
                scope.launch {
                    runCatching {
                        val info = Root.check(request = true)
                        if (!info.granted) {
                            rootNote = "未获得 Root 权限"
                            return@launch
                        }
                        val runner = productionRunner(info)
                        if (ksu) {
                            val result = A11yKsuModule.uninstall(runner)
                            if (result.exitCode == 0) {
                                A11yKeepAliveSettings.setKsuInstalled(ctx, false)
                                ksu = false
                                rootNote = "已卸载 Root 模块"
                            } else {
                                rootNote = "卸载失败（${result.exitCode}）"
                            }
                        } else {
                            val script = withContext(Dispatchers.IO) {
                                ctx.assets.open("a11y-keepalive/service.sh")
                                    .bufferedReader().use { it.readText() }
                            }
                            val result = A11yKsuModule.install(
                                runner, script, A11yKeepAliveSettings.targets(ctx),
                            )
                            if (result.exitCode == 0) {
                                A11yKsuModule.ensureRunning(runner)
                                A11yKeepAliveSettings.setKsuInstalled(ctx, true)
                                ksu = true
                                backend = A11yKeepAliveSettings.backendLabel(ctx)
                                rootNote = "已安装，开机自动运行"
                            } else {
                                rootNote = "安装失败（${result.exitCode}）"
                            }
                        }
                    }.onFailure { rootNote = "失败：${it.javaClass.simpleName}" }
                    busy = false
                }
            },
            Modifier.fillMaxWidth(),
        )
    }
    NoteCard(
        k,
        "列表只读取系统已安装的无障碍服务。保活默认关闭；LSPosed 钩子只在你仍开启系统无障碍时恢复断开的绑定，" +
            "不会新增你没启用的服务。安装 Root 模块后，service.sh 会在服务进程消失时按勾选列表重新启用；" +
            "你手动关闭系统无障碍、从系统设置移除服务或强行停止对应应用后不会自动打开。",
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
