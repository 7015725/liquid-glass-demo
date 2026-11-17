package com.flowglass.demo.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.demo.system.Grant
import com.flowglass.demo.system.KeepAlive
import com.flowglass.demo.system.PermItem
import com.flowglass.demo.system.Perms
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.RootGrantResult
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.xposed.SystemHookFamily
import com.flowglass.demo.system.RootGrants
import com.flowglass.demo.system.SysTick
import com.flowglass.demo.system.exec.LayerHeadline
import com.flowglass.demo.system.exec.LayerState
import com.flowglass.demo.system.exec.LayerStatus
import com.flowglass.demo.system.exec.label
import com.flowglass.engine.ExecutionLayer
import com.flowglass.demo.xposed.ModuleStatus
import com.flowglass.demo.xposed.method.MethodHookConfigPublisher
import com.flowglass.demo.xposed.method.MethodHookCatalog
import com.flowglass.demo.xposed.method.MethodHookSecrets
import com.flowglass.demo.xposed.method.MethodHookStatusRegistry
import com.flowglass.demo.xposed.method.MethodHookStore
import com.flowglass.engine.methodhook.MethodExtraction
import com.flowglass.engine.methodhook.MethodExtractionSource
import com.flowglass.engine.methodhook.MethodHookSpec
import com.flowglass.engine.methodhook.MethodHookStatus
import com.flowglass.engine.methodhook.MethodHookValidation
import com.flowglass.engine.methodhook.MethodHookNames
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

/** Settings sub pages. 0 = main list. */
object SettingsPage {
    const val Main = 0
    const val KeepAlive = 1
    const val Root = 2
    const val LSPosed = 3
    const val Permissions = 4
    const val Appearance = 5
    const val Layers = 6
    const val Backup = 7
    const val Variables = 8
    const val MethodHooks = 9
    fun title(p: Int) = when (p) {
        KeepAlive -> "App 保活"
        Root -> "Root 激活"
        LSPosed -> "LSPosed 激活"
        Permissions -> "权限声明管理"
        Appearance -> "外观"
        Layers -> "执行层级"
        Backup -> "备份与恢复"
        Variables -> "变量"
        MethodHooks -> "方法钩子"
        else -> "设置"
    }
}

@Composable
fun SettingsHost(
    state: AppState,
    page: Int,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    when (page) {
        SettingsPage.KeepAlive -> KeepAlivePage()
        SettingsPage.Root -> RootPage(state)
        SettingsPage.LSPosed -> LSPosedPage()
        SettingsPage.Permissions -> PermissionsPage()
        SettingsPage.Appearance -> AppearancePage(themeMode, onThemeModeChange)
        SettingsPage.Layers -> LayersPage()
        SettingsPage.Backup -> BackupRestorePage(state, themeMode, onThemeModeChange)
        SettingsPage.Variables -> VariableManagerPage(state)
        SettingsPage.MethodHooks -> MethodHooksPage(state)
        else -> SettingsMain(state, themeMode)
    }
}

// ================================================================ shared bits

private data class Row1(
    val ic: Ic,
    val label: String,
    val value: String? = null,
    val sub: String? = null,
    val upDown: Boolean = false,
    val valueColor: Color = C.Muted,
    val onClick: (() -> Unit)? = null,
)

@Composable
private fun GroupTitle(text: String, index: Int) {
    T(text, 13.sp, FontWeight.Bold, C.Muted, Modifier.padding(start = 8.dp, top = 6.dp).staggerIn(index))
}

/** A grouped card; its background doubles as a backdrop so glass toggles inside can refract it. */
@Composable
private fun GroupCard(index: Int, content: @Composable ColumnScope.(Backdrop) -> Unit) {
    val bd = rememberLayerBackdrop()
    Box(Modifier.fillMaxWidth().staggerIn(index).clip(RoundedRectangle(28.dp))) {
        Box(Modifier.matchParentSize().layerBackdrop(bd).background(C.Card))
        Column(Modifier.fillMaxWidth()) { content(bd) }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 60.dp, end = 20.dp).fillMaxWidth().height(1.dp).background(C.Divider))
}

@Composable
private fun ListRow(row: Row1, divider: Boolean, trailing: (@Composable () -> Unit)? = null) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .then(if (row.onClick != null) Modifier.pressable(0.98f) { row.onClick?.invoke() } else Modifier)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LineIcon(row.ic, C.OnSurfaceVar, 22.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                T(row.label, 16.sp, FontWeight.Medium)
                if (row.sub != null) {
                    Spacer(Modifier.height(2.dp))
                    T(row.sub, 12.sp, color = C.Muted, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (trailing != null) {
                trailing()
            } else {
                if (row.value != null) {
                    T(row.value, 14.sp, color = row.valueColor)
                    Spacer(Modifier.width(4.dp))
                }
                if (row.onClick != null || row.upDown) {
                    LineIcon(if (row.upDown) Ic.UpDown else Ic.ChevronRight, C.Muted, 18.dp)
                }
            }
        }
        if (divider) Divider()
    }
}

@Composable
private fun StatusPillButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (primary) C.Primary else C.GoodSoft, effect(), label = "pbBg")
    val fg by animateColorAsState(if (primary) C.OnPrimary else C.GoodText, effect(), label = "pbFg")
    Box(
        Modifier.clip(Capsule()).background(bg).pressable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)
    ) { T(text, 13.sp, FontWeight.SemiBold, fg) }
}

@Composable
private fun PrimaryButton(text: String, ic: Ic, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(Capsule()).background(C.Primary).pressable { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        LineIcon(ic, C.OnPrimary, 18.dp)
        Spacer(Modifier.width(8.dp))
        T(text, 15.sp, FontWeight.SemiBold, C.OnPrimary)
    }
}

/** Hero-style status card used by every sub page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(
    index: Int,
    ic: Ic,
    title: String,
    pill: String,
    ok: Boolean,
    big: String,
    unit: String,
    chips: List<String>,
    action: (@Composable () -> Unit)? = null,
    busy: Boolean = false,
) {
    PressCard(
        Modifier.fillMaxWidth().staggerIn(index),
        foreground = Modifier.shimmer(busy),
        background = {
            Box(Modifier.matchParentSize()) {
                Blob(
                    (if (ok) C.Primary else C.Muted).copy(alpha = 0.12f),
                    Modifier.size(200.dp).align(Alignment.TopEnd).padding(start = 0.dp),
                    seed = index + 5
                )
            }
        }
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(PolygonShape(M3EShapes.Clover4)).background(if (ok) C.PrimarySoft else C.Tonal),
                    contentAlignment = Alignment.Center
                ) { LineIcon(ic, if (ok) C.Primary else C.OnSurfaceVar, 18.dp) }
                Spacer(Modifier.width(10.dp))
                T(title, 18.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusPill(pill, ok)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedContent(big, transitionSpec = { fadeIn(effect()) togetherWith fadeOut(effect()) }, label = "big") {
                    T(it, 40.sp, FontWeight.Bold, tabular = true)
                }
                Spacer(Modifier.width(6.dp))
                T(unit, 13.sp, color = C.Muted, modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chips.forEach { TonalChip(it) } }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
private fun NoteCard(index: Int, text: String) {
    Row(
        Modifier.fillMaxWidth().staggerIn(index).clip(RoundedRectangle(24.dp)).background(C.Tonal).padding(16.dp)
    ) {
        LineIcon(Ic.Info, C.OnSurfaceVar, 18.dp)
        Spacer(Modifier.width(10.dp))
        T(text, 13.sp, color = C.OnSurfaceVar, lineHeight = 19.sp)
    }
}

@Composable
private fun tick(): Int = SysTick.tick

// ================================================================ main list

@Composable
private fun SettingsMain(state: AppState, themeMode: ThemeMode) {
    val ctx = LocalContext.current
    val t = tick()
    val keepReady = remember(t) { keepAliveReady(ctx) }
    val granted = remember(t) { Perms.all.count { it.check(ctx) } }
    val lsposed = remember(t) { ModuleStatus.isActive() }
    LaunchedEffect(t) {
        val info = Root.check(request = false)
        state.root = if (state.root?.granted == true) state.root else info
        LayerStatus.updateRoot(info)
        val active = runCatching { ModuleStatus.isActive() }.getOrDefault(false)
        LayerStatus.updateLsposed(active, systemHookReady = SystemHookSettings.isAlive(ctx))
    }
    val root = state.root
    val modes = listOf("省电", "均衡", "极速")

    var k = 0
    GroupTitle("运行与激活", k++)
    GroupCard(k++) {
        ListRow(Row1(Ic.Shield, "App 保活", "$keepReady/5 就绪", sub = "前台服务、自启动、电池白名单",
            valueColor = if (keepReady >= 4) C.GoodText else C.Muted) { state.settingsPage = SettingsPage.KeepAlive }, true)
        ListRow(Row1(Ic.Key, "Root 激活",
            when { root?.granted == true -> "已授权"; root?.suFound == true -> "待授权"; else -> "未检测到" },
            sub = "执行 Shell 类指令",
            valueColor = if (root?.granted == true) C.GoodText else C.Muted) { state.settingsPage = SettingsPage.Root }, true)
        ListRow(Row1(Ic.Puzzle, "LSPosed 激活", if (lsposed) "已激活" else "未激活", sub = "Xposed 模块增强",
            valueColor = if (lsposed) C.GoodText else C.Muted) { state.settingsPage = SettingsPage.LSPosed }, true)
        ListRow(Row1(Ic.Bolt, "方法钩子", MethodHookStatusRegistry.snapshot().count {
            it.status == com.flowglass.engine.methodhook.MethodHookStatus.INSTALLED
        }.let { if (it > 0) "$it 个已安装" else "只读观察" },
            sub = "指定目标应用的方法被调用时触发；默认关闭",
            valueColor = C.Muted) { state.settingsPage = SettingsPage.MethodHooks }, true)
        val layers = LayerStatus.current()
        val layersHeadline = LayerHeadline.of(layers)
        ListRow(Row1(Ic.Shield, "执行层级", layersHeadline.big,
            sub = "动作：${layersHeadline.actionLabel} · 事件：${layersHeadline.eventLabel} · Root：${layers.stateOf(ExecutionLayer.ROOT).label()} · LSPosed：${layers.stateOf(ExecutionLayer.XPOSED).label()}",
            valueColor = C.GoodText) { state.settingsPage = SettingsPage.Layers }, true)
        ListRow(Row1(Ic.Lock, "权限声明管理", "$granted/${Perms.all.size}", sub = "查看每项权限的用途并授权",
            valueColor = if (granted == Perms.all.size) C.GoodText else C.Muted) { state.settingsPage = SettingsPage.Permissions }, false)
    }
    GroupTitle("通用", k++)
    GroupCard(k++) {
        ListRow(Row1(Ic.Moon, "外观", themeMode.label, sub = themeMode.description) { state.settingsPage = SettingsPage.Appearance }, true)
        ListRow(Row1(Ic.Copy, "变量", sub = "内置、局部、全局变量与备注") { state.settingsPage = SettingsPage.Variables }, true)
        ListRow(Row1(Ic.Copy, "备份与恢复", sub = "导入导出指令、备份全部数据") { state.settingsPage = SettingsPage.Backup }, true)
        ListRow(Row1(Ic.Bell, "通知提醒") {}, true)
        ListRow(Row1(Ic.Gauge, "运行模式", modes[state.mode], upDown = true) { state.mode = (state.mode + 1) % 3 }, true)
        ListRow(Row1(Ic.Moon, "夜间静默", "23:00", upDown = true) {}, false)
    }
    GroupTitle("关于", k++)
    GroupCard(k++) {
        ListRow(Row1(Ic.Info, "版本", appVersionName(ctx)), true)
        ListRow(Row1(Ic.Puzzle, "开源组件", sub = "AndroidLiquidGlass · graphics-shapes") {}, false)
    }
}

@Composable
private fun AppearancePage(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    var k = 0
    GroupTitle("外观", k++)
    GroupCard(k++) {
        ThemeMode.entries.forEachIndexed { i, option ->
            ListRow(
                Row1(
                    ic = when (option) {
                        ThemeMode.SYSTEM -> Ic.Settings
                        ThemeMode.LIGHT -> Ic.Info
                        ThemeMode.DARK -> Ic.Moon
                    },
                    label = option.label,
                    sub = option.description,
                    onClick = { onModeChange(option) },
                ),
                divider = i < ThemeMode.entries.lastIndex,
            ) {
                if (option == mode) LineIcon(Ic.Bolt, C.Primary, 20.dp)
            }
        }
    }
    NoteCard(k, "跟随系统会随 Android 的深色模式即时切换；选择浅色或深色后，App 会记住你的选择。")
}

internal fun appVersionName(ctx: Context): String = runCatching {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    }
    info.versionName
}.getOrNull().orEmpty().ifBlank { "—" }

private fun keepAliveReady(ctx: Context): Int {
    var n = 0
    if (KeepAlive.isOn(ctx, KeepAlive.KEY_FGS)) n++
    if (KeepAlive.isOn(ctx, KeepAlive.KEY_BOOT)) n++
    if (KeepAlive.isOn(ctx, KeepAlive.KEY_JOB)) n++
    if (KeepAlive.isIgnoringBattery(ctx)) n++
    if (Perms.all.first { it.key == "notify" }.check(ctx)) n++
    return n
}

// ================================================================ App keep-alive

@Composable
private fun KeepAlivePage() {
    val ctx = LocalContext.current
    val t = tick()
    var fgs by remember(t) { mutableStateOf(KeepAlive.isOn(ctx, KeepAlive.KEY_FGS)) }
    var boot by remember(t) { mutableStateOf(KeepAlive.isOn(ctx, KeepAlive.KEY_BOOT)) }
    var job by remember(t) { mutableStateOf(KeepAlive.isOn(ctx, KeepAlive.KEY_JOB)) }
    val battery = remember(t) { KeepAlive.isIgnoringBattery(ctx) }
    val notify = remember(t) { Perms.all.first { it.key == "notify" }.check(ctx) }
    val ready = listOf(fgs, boot, job, battery, notify).count { it }

    var k = 0
    StatusCard(
        k++, Ic.Shield, "保活状态",
        if (fgs) "常驻中" else "未常驻", fgs,
        "$ready/5", "项已就绪",
        listOf(KeepAlive.vendorName(), "Android ${Build.VERSION.RELEASE}")
    )
    GroupTitle("常驻策略", k++)
    GroupCard(k++) { bd ->
        ListRow(Row1(Ic.Bell, "前台服务常驻", sub = "显示常驻通知，提升进程优先级"), true) {
            LiquidToggle({ fgs }, { fgs = it; KeepAlive.set(ctx, KeepAlive.KEY_FGS, it) }, bd)
        }
        ListRow(Row1(Ic.Refresh, "开机自启", sub = "设备重启后自动恢复常驻服务"), true) {
            LiquidToggle({ boot }, { boot = it; KeepAlive.set(ctx, KeepAlive.KEY_BOOT, it) }, bd)
        }
        ListRow(Row1(Ic.Timer, "定时巡检", sub = "每 15 分钟检查一次，服务被杀后重新拉起"), false) {
            LiquidToggle({ job }, { job = it; KeepAlive.set(ctx, KeepAlive.KEY_JOB, it) }, bd)
        }
    }
    GroupTitle("系统白名单", k++)
    GroupCard(k++) {
        ListRow(Row1(Ic.Battery, "忽略电池优化", sub = "避免省电策略冻结后台"), true) {
            StatusPillButton(if (battery) "已允许" else "去设置", !battery) { Perms.open(ctx, KeepAlive.batteryIntent(ctx)) }
        }
        ListRow(Row1(Ic.Rocket, "自启动管理", KeepAlive.vendorName(), sub = "在系统管家里允许自启动 / 关联启动") {
            KeepAlive.openAutoStart(ctx)
        }, true)
        ListRow(Row1(Ic.Phone, "后台运行权限", sub = "应用详情 → 耗电 → 允许后台运行") { KeepAlive.openAppDetails(ctx) }, true)
        ListRow(Row1(Ic.Lock, "锁定最近任务", sub = "在多任务界面长按或下拉本应用卡片，选择「锁定」"), false)
    }
    NoteCard(
        k++,
        "参考 KeepLiveService 的策略分级，这里只用了系统允许的低侵入方式。1 像素界面、无声音乐、防强停、后台弹界面等高侵入手段没有采用，它们耗电且容易被系统或应用商店判为违规。"
    )
}

// ================================================================ Root

@Composable
private fun RootPage(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var grantConfirm by remember { mutableStateOf(false) }
    var grantBusy by remember { mutableStateOf(false) }
    var grantResults by remember { mutableStateOf<List<RootGrantResult>>(emptyList()) }
    LaunchedEffect(Unit) { if (state.root == null) state.root = Root.check(request = false) }
    val r = state.root
    val granted = r?.granted == true
    var k = 0
    StatusCard(
        k++, Ic.Key, "Root 状态",
        when { granted -> "已授权"; r?.suFound == true -> "待授权"; else -> "未检测到" }, granted,
        if (granted) "已授权" else if (r?.suFound == true) "未授权" else "无 Root",
        if (granted) r?.uidLine ?: "" else "",
        listOf(r?.manager ?: "检测中…").filter { it != "—" }.ifEmpty { listOf("未安装 Root 管理器") },
        busy = busy,
        action = {
            PrimaryButton(
                if (granted) "重新检测" else "申请 Root 权限", Ic.Key,
                {
                    if (!busy) scope.launch {
                        busy = true
                        state.root = Root.check(request = true, refresh = true)
                        busy = false
                    }
                },
                Modifier.fillMaxWidth()
            )
        }
    )
    if (granted) {
        PrimaryButton(
            "用 Root 一键授予所需权限",
            Ic.Shield,
            { if (!grantBusy) grantConfirm = true },
            Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
    if (grantResults.isNotEmpty()) {
        GroupTitle("上次授予结果", k++)
        GroupCard(k++) {
            grantResults.forEachIndexed { i, result ->
                ListRow(
                    Row1(
                        Ic.Shield,
                        result.label,
                        value = if (result.success) "成功" else "失败",
                        sub = result.detail,
                        valueColor = if (result.success) C.GoodText else C.Bad,
                    ),
                    divider = i < grantResults.lastIndex,
                )
            }
        }
    }
    GroupTitle("检测详情", k++)
    GroupCard(k++) {
        ListRow(Row1(Ic.Puzzle, "Root 管理器", r?.manager ?: "—"), true)
        ListRow(Row1(Ic.Terminal, "su 路径", r?.suPath ?: "—"), true)
        ListRow(Row1(Ic.Shield, "当前身份", if (granted) "uid=0 (root)" else "普通应用"), false)
    }
    NoteCard(
        k++,
        if (granted) {
            "辅助授权只会执行固定命令，并在执行后逐项重新检查权限；不会自动运行，必须点击确认。"
        } else {
            "Root 只用于「执行脚本」这类需要 Shell 权限的指令。点击申请后，Magisk / KernelSU / APatch 会弹出授权框，请选择允许。"
        }
    )
    if (grantConfirm) {
        RootGrantDialog(
            count = RootGrants.specs(ctx).size,
            busy = grantBusy,
            onCancel = { if (!grantBusy) grantConfirm = false },
            onConfirm = {
                if (!grantBusy) {
                    grantConfirm = false
                    scope.launch {
                        grantBusy = true
                        grantResults = runCatching {
                            RootGrants.grant(ctx, checkNotNull(r))
                        }.getOrElse { error ->
                            listOf(
                                RootGrantResult(
                                    key = "root_grants",
                                    label = "Root 辅助授权",
                                    success = false,
                                    detail = error.message ?: "执行失败",
                                )
                            )
                        }
                        grantBusy = false
                        // Force the trigger host to re-check blocked rules after the grants land.
                        AppRuntime.of(ctx).state?.permissionsEpoch =
                            (AppRuntime.of(ctx).state?.permissionsEpoch ?: 0) + 1
                    }
                }
            },
        )
    }
}

@Composable
private fun RootGrantDialog(count: Int, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(C.ScrimSubtle),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(330.dp).clip(RoundedRectangle(24.dp)).background(C.Card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                T("用 Root 授予所需权限？", 18.sp, FontWeight.Bold)
                T(
                    "将逐项执行 $count 组固定命令（pm grant、appops、settings put、cmd notification 等），" +
                        "每项执行后都会重新检查结果。此操作不会自动运行，也不会修改规则。",
                    13.sp,
                    color = C.OnSurfaceVar,
                    lineHeight = 19.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).clip(Capsule()).background(C.Tonal)
                            .pressable { onCancel() },
                        contentAlignment = Alignment.Center,
                    ) { T("取消", 14.sp, FontWeight.SemiBold) }
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).clip(Capsule()).background(C.Primary)
                            .pressable { if (!busy) onConfirm() },
                        contentAlignment = Alignment.Center,
                    ) { T(if (busy) "执行中…" else "开始授予", 14.sp, FontWeight.SemiBold, C.OnPrimary) }
                }
            }
        }
    }
}

// ================================================================ LSPosed

@Composable
private fun LSPosedPage() {
    val ctx = LocalContext.current
    val t = tick()
    val active = remember(t) { ModuleStatus.isActive() }
    val version = remember(t) { ModuleStatus.frameworkVersion() }
    var hookEnabled by remember { mutableStateOf(SystemHookSettings.isEnabled(ctx)) }
    val hookStatus = remember(t) { SystemHookSettings.status(ctx) }
    // Entering the page is also a state-sync opportunity: if the app process was already alive
    // when the system hook loaded, this reply tells the hook the current switch value.
    LaunchedEffect(Unit) { SystemHookSettings.broadcastState(ctx) }
    val hookStatusText = when (hookStatus) {
        SystemHookSettings.Status.NOT_LOADED -> "钩子未加载；请检查 LSPosed 作用域并重启"
        SystemHookSettings.Status.LOADED_DISABLED -> "已加载但开关关闭"
        SystemHookSettings.Status.LOADED_WAITING -> "已加载；等待系统钩子心跳"
        SystemHookSettings.Status.NO_HOOKS -> "系统钩子已加载，但未找到可用的检测点"
        SystemHookSettings.Status.FAMILY_UNAVAILABLE -> "已加载，但已订阅的事件族缺少检测点"
        SystemHookSettings.Status.READY -> "已加载且开启；系统钩子心跳正常"
    }
    val managerInstalled = remember(t) {
        runCatching { ctx.packageManager.getPackageInfo("org.lsposed.manager", 0); true }.getOrDefault(false)
    }
    var k = 0
    StatusCard(
        k++, Ic.Puzzle, "模块状态",
        if (active) "已激活" else "未激活", active,
        if (active) "已激活" else "未激活", "",
        if (active) {
            listOf(
                "Xposed API $version",
                if (ModuleStatus.isModern()) "作用域：本应用 + 系统框架（system）" else "作用域：本应用 + 系统框架（android）",
            )
        } else listOf("需要 LSPosed 框架"),
        action = {
            PrimaryButton("打开 LSPosed 管理器", Ic.Puzzle, {
                val i = ctx.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
                if (i != null) Perms.open(ctx, i) else state_toast(ctx)
            }, Modifier.fillMaxWidth())
        }
    )
    GroupTitle("激活步骤", k++)
    GroupCard(k++) {
        val steps = listOf(
            Triple("刷入 LSPosed 框架", "通过 Magisk / KernelSU 安装 LSPosed 模块并重启", managerInstalled || active),
            Triple("在模块列表中启用", "打开 LSPosed 管理器 → 模块 → 流光自动化", active),
            Triple("勾选作用域", if (ModuleStatus.isModern()) "作用域勾选「流光自动化」和「系统框架（system）」" else "作用域勾选「流光自动化」和「系统框架（android）」", active),
            Triple("重启本应用", "在多任务里划掉后重新打开，这里会显示已激活", active),
        )
        steps.forEachIndexed { i, (title, sub, done) ->
            StepRow(i + 1, title, sub, done, i < steps.lastIndex)
        }
    }
    NoteCard(k++, "新版 LSPosed 的管理器可能没有桌面图标，可以从通知栏的 LSPosed 通知进入。")
    GroupTitle("系统钩子", k++)
    GroupCard(k++) { bd ->
        ListRow(
            Row1(
                Ic.Puzzle,
                "系统框架前台事件",
                hookStatusText,
            ),
            true,
        ) {
            LiquidToggle({ hookEnabled }, {
                hookEnabled = it
                SystemHookSettings.setEnabled(ctx, it)
            }, bd)
        }
    }
    GroupTitle("按需订阅事件", k++)
    GroupCard(k++) {
        val familyValue: (SystemHookFamily) -> String = { family ->
            val loaded = SystemHookSettings.isLoaded(ctx)
            val available = SystemHookSettings.availableFamilies(ctx)
            val subscribed = SystemHookSettings.subscribedFamilies(ctx)
            when {
                !loaded -> "钩子未加载"
                !hookEnabled -> "开关关闭"
                (available and family.mask) == 0 -> "未找到检测点"
                (subscribed and family.mask) != 0 -> "已按规则订阅"
                else -> "暂无规则使用"
            }
        }
        ListRow(
            Row1(
                Ic.Bolt,
                "应用进程启动 / 结束",
                familyValue(SystemHookFamily.PROCESS),
                sub = "trigger.app_process · 支持 package@userId",
            ),
            true,
        )
        ListRow(
            Row1(
                Ic.Info,
                "应用崩溃 / ANR",
                familyValue(SystemHookFamily.CRASH),
                sub = "trigger.app_crash · 只发送事件类型，不读取堆栈",
            ),
            true,
        )
        ListRow(
            Row1(
                Ic.Lock,
                "锁屏 / 解锁",
                familyValue(SystemHookFamily.KEYGUARD),
                sub = "trigger.keyguard · 只观察锁屏状态变化",
            ),
            true,
        )
        ListRow(
            Row1(
                Ic.Play,
                "媒体播放状态",
                familyValue(SystemHookFamily.MEDIA),
                sub = "trigger.media · 只观察播放/暂停/停止，不控制播放",
            ),
            true,
        )
        ListRow(
            Row1(
                Ic.Info,
                "前台服务",
                familyValue(SystemHookFamily.FOREGROUND_SERVICE),
                sub = "trigger.foreground_service · 只观察进入/退出，不读取通知",
            ),
            true,
        )
        ListRow(
            Row1(
                Ic.Search,
                "应用进程状态条件",
                if (SystemHookSettings.isLoaded(ctx)) "依赖上面的进程事件" else "钩子未加载",
                sub = "condition.app_process_state · 缓存过期按不满足处理",
                valueColor = C.Muted,
            ),
            false,
        )
    }
    NoteCard(
        k++,
        if (hookEnabled) {
            "系统钩子只观察前台切换、进程启动/结束、崩溃/ANR、锁屏状态、媒体播放状态和前台服务，并通过受保护广播回传，不修改系统行为。进程、崩溃/ANR、锁屏、媒体和前台服务只在有启用规则使用时订阅，默认不采集。若出现异常，可在 LSPosed 管理器里关闭本模块或禁用系统框架作用域，再重启设备。"
        } else {
            "默认关闭。启用后需要 LSPosed 的系统框架作用域；关闭后已注册的钩子不再发送任何事件。"
        },
    )
}

// ================================================================ method hooks

private data class MethodExtractionDraft(
    val name: String = "",
    val source: String = "参数",
    val argIndex: String = "",
    val path: String = "",
)

private fun MethodExtractionDraft.toModel(): MethodExtraction? {
    val resultSource = source.trim().startsWith("返回")
    return MethodExtraction(
        name = name.trim(),
        source = if (resultSource) MethodExtractionSource.RESULT else MethodExtractionSource.ARGUMENT,
        argIndex = if (resultSource) null else argIndex.trim().toIntOrNull(),
        path = path.trim(),
    )
}

@Composable
private fun MethodHooksPage(state: AppState) {
    val ctx = LocalContext.current
    val store = remember { MethodHookStore(File(ctx.filesDir, "method-hooks.json")) }
    var loaded by remember { mutableStateOf(store.load()) }
    var hooks by remember { mutableStateOf(loaded.hooks) }
    var editing by remember { mutableStateOf<MethodHookSpec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val t = tick()
    val statuses = remember(t) { MethodHookStatusRegistry.snapshot() }
    var k = 0

    LaunchedEffect(Unit) { MethodHookCatalog.refresh(ctx) }

    StatusCard(
        k++, Ic.Bolt, "方法钩子",
        if (hooks.isNotEmpty()) "已配置" else "未配置",
        hooks.isNotEmpty(),
        hooks.size.toString(), "个",
        listOf(
            "只读观察",
            "默认关闭",
            if (ModuleStatus.isActive()) "LSPosed 已激活" else "等待 LSPosed",
        ),
    )
    if (loaded.error != null) NoteCard(k++, loaded.error!!)
    if (notice != null) NoteCard(k++, notice!!)

    GroupTitle("已配置钩子", k++)
    GroupCard(k++) {
        if (hooks.isEmpty()) {
            T("还没有方法钩子。先新增一个目标，再在规则里添加「方法钩子」触发器。",
                13.sp, color = C.Muted, modifier = Modifier.padding(20.dp))
        }
        hooks.forEachIndexed { index, hook ->
            val own = statuses.filter { it.hookId == hook.id }
            val installed = own.count { it.status == MethodHookStatus.INSTALLED }
            val failed = own.count {
                it.status == MethodHookStatus.FAILED || it.status == MethodHookStatus.NOT_FOUND ||
                    it.status == MethodHookStatus.AMBIGUOUS
            }
            val value = when {
                installed > 0 -> "$installed 个进程已安装"
                failed > 0 -> "$failed 个进程未安装"
                else -> "未上报"
            }
            ListRow(
                Row1(
                    Ic.Bolt,
                    "${hook.packageName}@${hook.userId} · ${hook.methodName}",
                    value,
                    sub = "${hook.className} · 标识 ${hook.id}" +
                        if (hook.extractions.isNotEmpty()) " · 变量 ${hook.extractions.joinToString("、") { it.name }}" else "",
                    valueColor = if (installed > 0) C.GoodText else C.Muted,
                ),
                index < hooks.lastIndex,
            ) {
                StatusPillButton("编辑", false) { editing = hook }
            }
        }
    }
    GroupCard(k++) {
        PrimaryButton("新增方法钩子", Ic.Plus, { creating = true }, Modifier.fillMaxWidth())
    }
    NoteCard(
        k++,
        "勾选 LSPosed 作用域即表示信任该目标应用。第一版只读：不修改参数、返回值，也不跳过原方法；" +
            "目标应用的同 UID 代码理论上能读到自己的 token，本机制防止其他应用伪造和重放，不做同 UID 的密码学隔离。" +
            "每钩子默认 60 次/分钟、突发 10 次，超限只上报限流状态。",
    )
    NoteCard(
        k++,
        "目标应用必须已在 LSPosed 作用域中勾选，并在重启后重新进入目标进程；安装结果按进程显示。" +
            "规则里用 {订单号} 这样的写法引用提取变量；固定变量为 {钩子应用}、{钩子用户}、{钩子进程}、" +
            "{钩子方法}、{钩子签名}、{钩子标识}。",
    )

    if (creating || editing != null) {
        MethodHookEditorDialog(
            initial = editing,
            existingNames = run {
                // The dialog must validate against the same catalogue the variable manager shows;
                // refresh synchronously so an immediate first open does not miss the last save.
                MethodHookCatalog.refresh(ctx)
                VariableCatalog.forManager(state)
                    .map { it.name }
                    .toSet()
                    .minus(
                        editing?.extractions
                            ?.map { MethodHookNames.normalizeExtractionName(it.name) }
                            ?.toSet()
                            .orEmpty(),
                    )
            },
            onDismiss = { creating = false; editing = null },
            onSave = { spec ->
                val next = hooks.filterNot { it.id == spec.id } + spec
                try {
                    store.save(next)
                    hooks = next
                    MethodHookCatalog.refresh(ctx)
                    MethodHookConfigPublisher.publish(ctx)
                    if (!spec.enabled) MethodHookSecrets(ctx).remove(spec.id)
                    loaded = store.load()
                    notice = "已保存 ${spec.id}；目标应用下次启动或重启进程后会重新安装。"
                    creating = false
                    editing = null
                } catch (failure: Throwable) {
                    notice = "保存失败：${failure.message ?: failure.javaClass.simpleName}"
                }
            },
            onDelete = editing?.let { old ->
                {
                    val next = hooks.filterNot { it.id == old.id }
                    runCatching {
                        store.save(next)
                        MethodHookSecrets(ctx).remove(old.id)
                        MethodHookConfigPublisher.publish(ctx)
                    }
                    hooks = next
                    MethodHookCatalog.refresh(ctx)
                    loaded = store.load()
                    notice = "已删除 ${old.id}。"
                    creating = false
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun MethodHookEditorDialog(
    initial: MethodHookSpec?,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (MethodHookSpec) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var packageName by remember(initial) { mutableStateOf(initial?.packageName.orEmpty()) }
    var userId by remember(initial) { mutableStateOf(initial?.userId?.toString() ?: "0") }
    var className by remember(initial) { mutableStateOf(initial?.className.orEmpty()) }
    var methodName by remember(initial) { mutableStateOf(initial?.methodName.orEmpty()) }
    var paramCount by remember(initial) { mutableStateOf(initial?.paramCount?.toString().orEmpty()) }
    var paramTypes by remember(initial) { mutableStateOf(initial?.paramTypes?.joinToString(",").orEmpty()) }
    var maxCalls by remember(initial) { mutableStateOf(initial?.maxCallsPerMinute?.toString() ?: "60") }
    var burst by remember(initial) { mutableStateOf(initial?.burst?.toString() ?: "10") }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: false) }
    var drafts by remember(initial) {
        mutableStateOf(
            initial?.extractions?.map {
                MethodExtractionDraft(
                    name = it.name,
                    source = if (it.source == MethodExtractionSource.RESULT) "返回值" else "参数",
                    argIndex = it.argIndex?.toString().orEmpty(),
                    path = it.path,
                )
            } ?: emptyList()
        )
    }
    var error by remember(initial) { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(16.dp).clip(RoundedRectangle(28.dp)).background(C.Card)
                .verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(if (initial == null) "新增方法钩子" else "编辑方法钩子", 20.sp, FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                StatusPillButton("取消", false, onDismiss)
            }
            Spacer(Modifier.height(14.dp))
            LabeledHint("目标应用包名（必须已在 LSPosed 作用域勾选）")
            ParameterTextField(packageName, "method-hook-package") { packageName = it }
            LabeledHint("用户 ID（主用户 0，分身填对应 user id）")
            ParameterTextField(userId, "method-hook-user") { userId = it }
            LabeledHint("类名（全限定，例如 com.example.OrderApi）")
            ParameterTextField(className, "method-hook-class") { className = it }
            LabeledHint("方法名")
            ParameterTextField(methodName, "method-hook-method") { methodName = it }
            LabeledHint("参数个数（可留空；重载必须收敛到唯一一个）")
            ParameterTextField(paramCount, "method-hook-count") { paramCount = it }
            LabeledHint("参数类型（逗号分隔的全限定名，可留空；例如 java.lang.String,int）")
            ParameterTextField(paramTypes, "method-hook-types") { paramTypes = it }
            LabeledHint("每分钟上限 / 突发上限")
            ParameterTextField(maxCalls, "method-hook-rate") { maxCalls = it }
            ParameterTextField(burst, "method-hook-burst") { burst = it }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("启用", 15.sp, FontWeight.Medium, modifier = Modifier.weight(1f))
                StatusPillButton(if (enabled) "已启用" else "已停用", enabled) { enabled = !enabled }
            }
            Spacer(Modifier.height(14.dp))
            T("提取变量（名字允许中文；重复、内置名会被拒绝）", 15.sp, FontWeight.SemiBold)
            drafts.forEachIndexed { index, draft ->
                Spacer(Modifier.height(8.dp))
                ParameterTextField(draft.name, "method-hook-extract-name-$index") {
                    drafts = drafts.toMutableList().also { list -> list[index] = draft.copy(name = it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPillButton(if (draft.source.startsWith("返回")) "返回值" else "参数", false) {
                        drafts = drafts.toMutableList().also { list ->
                            list[index] = draft.copy(source = if (draft.source.startsWith("返回")) "参数" else "返回值")
                        }
                    }
                    if (!draft.source.startsWith("返回")) {
                        ParameterTextField(draft.argIndex, "method-hook-extract-index-$index") {
                            drafts = drafts.toMutableList().also { list -> list[index] = draft.copy(argIndex = it) }
                        }
                    }
                }
                ParameterTextField(draft.path, "method-hook-extract-path-$index") {
                    drafts = drafts.toMutableList().also { list -> list[index] = draft.copy(path = it) }
                }
                if (drafts.size > 1) {
                    StatusPillButton("删除提取项", false) {
                        drafts = drafts.toMutableList().also { it.removeAt(index) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            StatusPillButton("添加提取项", false) {
                drafts = drafts + MethodExtractionDraft()
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                T(error!!, 13.sp, color = C.Muted)
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(if (initial == null) "保存钩子" else "保存修改", Ic.Edit, {
                val spec = MethodHookSpec(
                    id = initial?.id ?: "hook-${System.currentTimeMillis()}",
                    enabled = enabled,
                    packageName = packageName.trim(),
                    userId = userId.trim().toIntOrNull() ?: 0,
                    className = className.trim(),
                    methodName = methodName.trim(),
                    paramCount = paramCount.trim().toIntOrNull(),
                    paramTypes = paramTypes.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                    extractions = drafts.mapNotNull { it.toModel() },
                    maxCallsPerMinute = maxCalls.trim().toIntOrNull() ?: 60,
                    burst = burst.trim().toIntOrNull() ?: 10,
                )
                val errors = MethodHookValidation.errors(spec, existingNames)
                if (errors.isNotEmpty()) {
                    error = errors.joinToString("\n")
                } else {
                    onSave(spec)
                }
            }, Modifier.fillMaxWidth())
            if (onDelete != null) {
                Spacer(Modifier.height(10.dp))
                StatusPillButton("删除这个钩子", false, onDelete)
            }
        }
    }
}

@Composable
private fun LabeledHint(text: String) {
    Spacer(Modifier.height(8.dp))
    T(text, 12.sp, color = C.Muted, lineHeight = 17.sp)
}

private fun state_toast(ctx: Context) {
    android.widget.Toast.makeText(ctx, "未找到 LSPosed 管理器，请从通知栏进入", android.widget.Toast.LENGTH_SHORT).show()
}

// ================================================================ execution layers

@Composable
private fun LayersPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(LayerStatus.current()) }
    val headline = LayerHeadline.of(snapshot)
    LaunchedEffect(Unit) {
        // Entering this page must not depend on FlowApp.onCreate: if the hook loaded while the
        // process was already alive, this state broadcast lets it replay its loaded announcement.
        SystemHookSettings.broadcastState(ctx)
        snapshot = LayerStatus.refresh(requestRoot = false, systemHookReady = SystemHookSettings.isAlive(ctx))
    }
    var k = 0
    StatusCard(
        k++, Ic.Shield, "执行层级",
        headline.pill,
        headline.ok,
        headline.big, headline.unit,
        listOf(
            "动作：${headline.actionLabel}",
            "事件：${headline.eventLabel}",
            "Root ${snapshot.stateOf(ExecutionLayer.ROOT).label()}",
            "LSPosed ${snapshot.stateOf(ExecutionLayer.XPOSED).label()}",
        ),
        busy = busy,
        action = {
            PrimaryButton("刷新状态", Ic.Refresh, {
                if (!busy) {
                    SystemHookSettings.broadcastState(ctx)
                    scope.launch {
                        busy = true
                        snapshot = LayerStatus.refresh(requestRoot = false, systemHookReady = SystemHookSettings.isAlive(ctx))
                        busy = false
                    }
                }
            }, Modifier.fillMaxWidth())
        },
    )
    GroupTitle("各层状态", k++)
    GroupCard(k++) {
        ListRow(Row1(Ic.Shield, "普通", snapshot.normal.label(),
            sub = "应用进程内实现；具体权限按组件声明检查",
            valueColor = if (snapshot.normal == LayerState.READY) C.GoodText else C.Muted), true)
        ListRow(Row1(Ic.Key, "Root", snapshot.stateOf(ExecutionLayer.ROOT).label(),
            sub = snapshot.details[ExecutionLayer.ROOT] ?: "未检测",
            valueColor = if (snapshot.stateOf(ExecutionLayer.ROOT) == LayerState.READY) C.GoodText else C.Muted), true)
        ListRow(Row1(Ic.Puzzle, "LSPosed", snapshot.stateOf(ExecutionLayer.XPOSED).label(),
            sub = snapshot.details[ExecutionLayer.XPOSED] ?: "未检测",
            valueColor = if (snapshot.stateOf(ExecutionLayer.XPOSED) == LayerState.READY) C.GoodText else C.Muted), false)
    }
    NoteCard(
        k++,
        "动作按 Root → 普通选择，事件触发器按 LSPosed → Root → 普通选择。Root 自动路径只复用已有授权，不主动弹授权框；LSPosed 只有在系统前台钩子就绪后才算可用。",
    )
}

@Composable
private fun StepRow(n: Int, title: String, sub: String, done: Boolean, divider: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                if (done) {
                    CheckBadge(true, 26.dp)
                } else {
                    Box(Modifier.size(26.dp).clip(Capsule()).background(C.Tonal), contentAlignment = Alignment.Center) {
                        T("$n", 13.sp, FontWeight.Bold, C.OnSurfaceVar)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                T(title, 15.sp, FontWeight.SemiBold, if (done) C.OnSurface else C.OnSurfaceVar)
                T(sub, 12.sp, color = C.Muted, lineHeight = 16.sp)
            }
        }
        if (divider) Box(Modifier.padding(start = 60.dp, end = 20.dp).fillMaxWidth().height(1.dp).background(C.Divider))
    }
}

// ================================================================ Permissions

@Composable
private fun PermissionsPage() {
    val ctx = LocalContext.current
    val t = tick()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { SysTick.tick++ }
    val status = remember(t) { Perms.all.associate { it.key to it.check(ctx) } }
    val granted = status.values.count { it }
    val total = Perms.all.size
    val requiredMissing = Perms.all.count { it.required && status[it.key] != true }
    val progress by animateFloatAsState(granted / total.toFloat(), spatial(), label = "permProgress")

    fun request(p: PermItem) {
        if (p.key == "notify" && (Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            Perms.open(ctx, android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            return
        }
        when (val g = p.grant) {
            is Grant.Runtime -> launcher.launch(g.permission)
            is Grant.Page -> Perms.open(ctx, g.intent(ctx))
        }
    }

    var k = 0
    StatusCard(
        k++, Ic.Lock, "授权概况",
        if (requiredMissing == 0) "必需项已齐" else "缺 $requiredMissing 项必需", requiredMissing == 0,
        "$granted/$total", "项已授予",
        listOf("必需 ${Perms.all.count { it.required }} 项", "可选 ${Perms.all.count { !it.required }} 项"),
        action = {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(Capsule()).background(C.Tonal)) {
                Box(Modifier.fillMaxWidth(progress).height(8.dp).clip(Capsule()).background(C.Primary))
            }
        }
    )
    listOf(true to "必需权限", false to "可选权限").forEach { (req, title) ->
        val items = Perms.all.filter { it.required == req }
        GroupTitle(title, k++)
        GroupCard(k++) {
            items.forEachIndexed { i, p ->
                val ok = status[p.key] == true
                ListRow(Row1(p.ic, p.name, sub = p.purpose), i < items.lastIndex) {
                    StatusPillButton(if (ok) "已授予" else "去授权", !ok) { request(p) }
                }
            }
        }
    }
    NoteCard(k++, "每项权限都只用于上面写明的用途，不收集、不上传任何数据。授权后返回本页会自动刷新状态。")
}


// ================================================================ backup & restore

@Composable
private fun BackupRestorePage(
    state: AppState,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ParsedImport?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = exportText
        exportText = null
        if (uri != null && text != null) {
            scope.launch {
                val error = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                            ?: error("无法打开目标文件")
                    }
                }.exceptionOrNull()
                notice = if (error == null) "完整备份已导出" else "导出失败：${error.message.orEmpty()}"
            }
        }
    }
    fun open(uri: android.net.Uri, expected: ImportKind) {
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取文件")
                }
                val parsed = parseImport(text, state)
                require(parsed.kind == expected) {
                    if (expected == ImportKind.BACKUP) "这不是完整备份文件" else "这是完整备份文件，请使用「从备份恢复」"
                }
                pending = parsed
                notice = null
            } catch (error: Exception) {
                notice = "读取失败：${error.message.orEmpty()}"
            }
        }
    }
    val commandLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { open(it, ImportKind.COMMAND) }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { open(it, ImportKind.BACKUP) }
    }

    var k = 0
    GroupTitle("备份与恢复", k++)
    GroupCard(k++) {
        ListRow(
            Row1(Ic.Share, "导出完整备份", sub = "全部指令、全局变量和设置") {
                runCatching { exportBackupJson(state, themeMode, readBackupAppSettings(context)) }
                    .onSuccess { text ->
                        exportText = text
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        exportLauncher.launch("flowglass-backup-$stamp.json")
                    }
                    .onFailure { notice = "导出失败：${it.message.orEmpty()}" }
            },
            true,
        )
        ListRow(
            Row1(Ic.Copy, "从文件导入指令", sub = "导入前会预览内容，不会覆盖现有指令") {
                commandLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            true,
        )
        ListRow(
            Row1(Ic.Copy, "从剪贴板导入", sub = "JSON 文本；导入前会预览") {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) {
                    notice = "剪贴板里没有可导入的文本"
                } else {
                    scope.launch {
                        try {
                            val parsed = parseImport(text, state)
                            require(parsed.kind == ImportKind.COMMAND) { "剪贴板是完整备份，请使用「从备份恢复」" }
                            pending = parsed
                        } catch (error: Exception) {
                            notice = "导入失败：${error.message.orEmpty()}"
                        }
                    }
                }
            },
            true,
        )
        ListRow(
            Row1(Ic.Refresh, "从备份恢复", sub = "恢复前自动备份当前数据") {
                backupLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            false,
        )
    }
    NoteCard(k++, "导入的文件一律视为不可信，导入预览会列出触发器、条件和任务；含 Root Shell、HTTP 请求、执行另一条指令或脚本时会用红色提示，Root-only 动作会标注「需要 Root」。自动指令导入后默认关闭。备份包含 App 内设置，但不包含 Root 授权；恢复失败不会覆盖原数据。")
    notice?.let { NoteCard(k++, it) }

    pending?.let { parsed ->
        ImportPreviewDialog(
            preview = parsed.preview,
            onDismiss = { pending = null },
            onConfirm = {
                try {
                    if (parsed.kind == ImportKind.BACKUP) {
                        val safety = restoreBackup(context, state, parsed)
                        val theme = parsed.preview.themeMode
                        if (theme != null) {
                            AppearanceSettings.write(context, theme)
                            onThemeModeChange(theme)
                        }
                        parsed.settings?.let { applyBackupAppSettings(context, it.appSettings) }
                        notice = "已恢复备份；原数据已保存到 ${safety.name}"
                    } else {
                        val count = parsed.preview.commands.size
                        state.applyCommandImport(parsed)
                        notice = "已导入 $count 条指令"
                    }
                    pending = null
                } catch (error: Exception) {
                    notice = "操作失败，原数据未改变：${error.message.orEmpty()}"
                }
            },
        )
    }
}
