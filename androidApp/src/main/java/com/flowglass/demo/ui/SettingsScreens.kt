package com.flowglass.demo.ui

import android.content.Context
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.demo.system.Grant
import com.flowglass.demo.system.KeepAlive
import com.flowglass.demo.system.PermItem
import com.flowglass.demo.system.Perms
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.SysTick
import com.flowglass.demo.xposed.ModuleStatus
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch

/** Settings sub pages. 0 = main list. */
object SettingsPage {
    const val Main = 0
    const val KeepAlive = 1
    const val Root = 2
    const val LSPosed = 3
    const val Permissions = 4
    fun title(p: Int) = when (p) {
        KeepAlive -> "App 保活"
        Root -> "Root 激活"
        LSPosed -> "LSPosed 激活"
        Permissions -> "权限声明管理"
        else -> "设置"
    }
}

@Composable
fun SettingsHost(state: AppState, page: Int) {
    when (page) {
        SettingsPage.KeepAlive -> KeepAlivePage()
        SettingsPage.Root -> RootPage(state)
        SettingsPage.LSPosed -> LSPosedPage()
        SettingsPage.Permissions -> PermissionsPage()
        else -> SettingsMain(state)
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
    val fg by animateColorAsState(if (primary) Color.White else C.GoodText, effect(), label = "pbFg")
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
        LineIcon(ic, Color.White, 18.dp)
        Spacer(Modifier.width(8.dp))
        T(text, 15.sp, FontWeight.SemiBold, Color.White)
    }
}

/** Hero-style status card used by every sub page. */
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips.forEach { TonalChip(it) } }
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
private fun SettingsMain(state: AppState) {
    val ctx = LocalContext.current
    val t = tick()
    val keepReady = remember(t) { keepAliveReady(ctx) }
    val granted = remember(t) { Perms.all.count { it.check(ctx) } }
    val lsposed = remember(t) { ModuleStatus.isActive() }
    LaunchedEffect(t) { state.root = Root.check(request = false).let { if (state.root?.granted == true) state.root else it } }
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
        ListRow(Row1(Ic.Lock, "权限声明管理", "$granted/${Perms.all.size}", sub = "查看每项权限的用途并授权",
            valueColor = if (granted == Perms.all.size) C.GoodText else C.Muted) { state.settingsPage = SettingsPage.Permissions }, false)
    }
    GroupTitle("通用", k++)
    GroupCard(k++) {
        ListRow(Row1(Ic.Bell, "通知提醒") {}, true)
        ListRow(Row1(Ic.Gauge, "运行模式", modes[state.mode], upDown = true) { state.mode = (state.mode + 1) % 3 }, true)
        ListRow(Row1(Ic.Moon, "夜间静默", "23:00", upDown = true) {}, false)
    }
    GroupTitle("关于", k++)
    GroupCard(k++) {
        ListRow(Row1(Ic.Info, "版本", "0.4.1"), true)
        ListRow(Row1(Ic.Puzzle, "开源组件", sub = "AndroidLiquidGlass · graphics-shapes") {}, false)
    }
}

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
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
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
                        state.root = Root.check(request = true)
                        busy = false
                    }
                },
                Modifier.fillMaxWidth()
            )
        }
    )
    GroupTitle("检测详情", k++)
    GroupCard(k++) {
        ListRow(Row1(Ic.Puzzle, "Root 管理器", r?.manager ?: "—"), true)
        ListRow(Row1(Ic.Terminal, "su 路径", r?.suPath ?: "—"), true)
        ListRow(Row1(Ic.Shield, "当前身份", if (granted) "uid=0 (root)" else "普通应用"), false)
    }
    NoteCard(
        k++,
        "Root 只用于「执行脚本」这类需要 Shell 权限的指令。点击申请后，Magisk / KernelSU / APatch 会弹出授权框，请选择允许。"
    )
}

// ================================================================ LSPosed

@Composable
private fun LSPosedPage() {
    val ctx = LocalContext.current
    val t = tick()
    val active = remember(t) { ModuleStatus.isActive() }
    val version = remember(t) { ModuleStatus.frameworkVersion() }
    val managerInstalled = remember(t) {
        runCatching { ctx.packageManager.getPackageInfo("org.lsposed.manager", 0); true }.getOrDefault(false)
    }
    var k = 0
    StatusCard(
        k++, Ic.Puzzle, "模块状态",
        if (active) "已激活" else "未激活", active,
        if (active) "已激活" else "未激活", "",
        if (active) listOf("Xposed API $version", "作用域：本应用") else listOf("需要 LSPosed 框架"),
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
            Triple("勾选作用域", "作用域勾选「流光自动化」本身", active),
            Triple("重启本应用", "在多任务里划掉后重新打开，这里会显示已激活", active),
        )
        steps.forEachIndexed { i, (title, sub, done) ->
            StepRow(i + 1, title, sub, done, i < steps.lastIndex)
        }
    }
    NoteCard(k++, "新版 LSPosed 的管理器可能没有桌面图标，可以从通知栏的 LSPosed 通知进入。")
}

private fun state_toast(ctx: Context) {
    android.widget.Toast.makeText(ctx, "未找到 LSPosed 管理器，请从通知栏进入", android.widget.Toast.LENGTH_SHORT).show()
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
