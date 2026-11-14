package com.flowglass.demo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.liquid.LiquidToggle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun HomeScreen(state: AppState, onOpenTab: (Int) -> Unit) {
    var i = 0
    HeroCard(state, Modifier.staggerIn(i++))
    QuickOneTaps(state, onOpenTab, Modifier.staggerIn(i++))
    MetricsCard(state, Modifier.staggerIn(i++))
    RecentRuns(state, Modifier.staggerIn(i++))
    ModeCard(state, Modifier.staggerIn(i++))
}

@Composable
private fun HeroCard(state: AppState, modifier: Modifier) {
    LaunchedEffect(state.running) {
        while (isActive && state.running) {
            delay(1000)
            state.uptime += 1
        }
    }
    val h = state.uptime / 3600
    val m = (state.uptime / 60) % 60
    val s = state.uptime % 60
    val uptime = "%d:%02d:%02d".format(h, m, s)
    val enabled = state.autoCmds.count { it.enabled }
    val listening = if (state.running && com.flowglass.demo.system.trigger.TriggerStatus.serviceActive)
        com.flowglass.demo.system.trigger.TriggerStatus.listeningRules else 0
    val sources = com.flowglass.demo.system.trigger.TriggerStatus.registeredSources

    PressCard(
        modifier.fillMaxWidth(),
        background = {
            Box(Modifier.matchParentSize()) {
                Blob(
                    C.Primary.copy(alpha = 0.13f),
                    Modifier.size(170.dp).align(Alignment.TopEnd).offset(x = 46.dp, y = 40.dp),
                    seed = 3
                )
            }
        }
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineIcon(Ic.Bolt, C.OnSurface, 20.dp)
                Spacer(Modifier.width(8.dp))
                T("自动化引擎", 18.sp, FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                StatusPill(if (state.running) "运行中" else "已暂停", state.running)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(PolygonShape(M3EShapes.Clover4)).background(C.PrimarySoft),
                            contentAlignment = Alignment.Center
                        ) { LineIcon(Ic.Grid, C.Primary, 20.dp) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            T("自动指令", 14.sp, FontWeight.SemiBold)
                            T(
                                if (state.running) "正在监听 $listening 条自动指令" else "引擎已暂停",
                                12.sp, color = C.Muted,
                            )
                            if (state.running) {
                                T("已注册 $sources 个监听源", 11.sp, color = C.Muted)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        RollingText("$listening", 44.sp, FontWeight.Bold, if (state.running) C.OnSurface else C.Muted)
                        Spacer(Modifier.width(4.dp))
                        T("/ ${state.autoCmds.size} 条", 14.sp, color = C.Muted, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TonalChip("今日 ${state.todayRuns} 次")
                        TonalChip(listOf("省电", "均衡", "极速")[state.mode])
                    }
                }
                ScallopButton(state.running, { state.running = !state.running })
            }
            Spacer(Modifier.height(20.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    T("运行时长", 13.sp, color = C.Muted)
                    Spacer(Modifier.height(2.dp))
                    RollingText(uptime, 17.sp, FontWeight.Bold)
                }
                Meta("一键指令", "${state.oneTaps.size} 条", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickOneTaps(state: AppState, onOpenTab: (Int) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val picks = state.sorted(auto = false).take(3)
    PressCard(modifier.fillMaxWidth()) {
        Column {
            SectionTitle("常用一键指令") {
                Row(Modifier.pressable { onOpenTab(2) }, verticalAlignment = Alignment.CenterVertically) {
                    T("全部", 13.sp, FontWeight.Medium, C.Primary)
                    LineIcon(Ic.ChevronRight, C.Primary, 16.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                picks.forEach { cmd ->
                    val exec = state.executing[cmd.id] ?: 0
                    val bg by animateColorAsState(
                        when (exec) { 1 -> C.PrimarySoft; 2 -> C.GoodSoft; 3 -> C.Bad; else -> C.Tonal }, effect(), label = "qBg"
                    )
                    Column(
                        Modifier.weight(1f).height(112.dp).testTag("quick-execute-${cmd.id}")
                            .clip(RoundedRectangle(24.dp)).background(bg)
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                if (exec == 0) onClick { state.execute(cmd, scope); true } else disabled()
                            }
                            .pressable(0.94f) { if (exec == 0) state.execute(cmd, scope) }
                            .padding(12.dp)
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(PolygonShape(M3EShapes.Clover4)).background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            when (exec) {
                                1 -> Spinner(C.Primary, 16.dp)
                                2 -> CheckBadge(true, 18.dp)
                                3 -> LineIcon(Ic.Info, C.Bad, 18.dp)
                                else -> LineIcon(cmd.ic, C.OnSurface, 18.dp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        T(cmd.title, 14.sp, FontWeight.Bold, if (exec == 3) Color.White else C.OnSurface, maxLines = 1)
                        T(
                            when (exec) { 1 -> "执行中…"; 2 -> "已完成"; 3 -> "失败"; else -> "轻点执行" },
                            12.sp, color = when (exec) { 2 -> C.GoodText; 3 -> Color.White; else -> C.Muted }
                        )
                    }
                }
                repeat(3 - picks.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RecentRuns(state: AppState, modifier: Modifier) {
    var selectedLog by remember { mutableStateOf<RunLog?>(null) }
    val backdrop = rememberLayerBackdrop()
    PressCard(modifier.fillMaxWidth().layerBackdrop(backdrop), contentPadding = 0.dp) {
        Column {
            SectionTitle("最近执行", Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp)) {
                T("今日 ${state.todayRuns} 次", 13.sp, color = C.Muted)
            }
            val logs = state.logs.take(5)
            logs.forEachIndexed { i, log ->
                key(log.id) {
                    Column(Modifier.staggerIn(0)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedLog = log }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(Capsule()).background(C.Tonal),
                                contentAlignment = Alignment.Center
                            ) { LineIcon(log.ic, C.OnSurfaceVar, 18.dp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                T(log.title, 15.sp, FontWeight.SemiBold, maxLines = 1)
                                T("${log.reason} · ${log.time}", 12.sp, color = C.Muted)
                            if (!log.ok && !log.skipped && log.detail.isNotBlank()) {
                                T(log.detail, 12.sp, color = C.Bad, maxLines = 2)
                            }
                            if (log.skipped && log.detail.isNotBlank()) {
                                T(log.detail, 12.sp, color = C.Muted, maxLines = 2)
                            }
                        }
                        Row(
                            Modifier.clip(Capsule()).background(
                                when {
                                    log.skipped -> C.Tonal
                                    log.ok -> C.GoodSoft
                                    else -> Color(0xFFFDE4E4)
                                }
                            ).padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            T(
                                when {
                                    log.skipped -> "跳过"
                                    log.ok -> "成功"
                                    else -> "失败"
                                },
                                12.sp, FontWeight.SemiBold,
                                when {
                                    log.skipped -> C.OnSurfaceVar
                                    log.ok -> C.GoodText
                                    else -> C.Bad
                                },
                            )
                        }
                        }
                        if (i < logs.lastIndex) {
                            Box(Modifier.padding(start = 68.dp, end = 20.dp).fillMaxWidth().height(1.dp).background(C.Divider))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    selectedLog?.let { log -> RunLogPanel(log, backdrop) { selectedLog = null } }
}

@Composable
private fun Meta(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        T(label, 13.sp, color = C.Muted)
        Spacer(Modifier.height(2.dp))
        RollingText(value, 17.sp, FontWeight.Bold)
    }
}

@Composable
private fun MetricsCard(state: AppState, modifier: Modifier) {
    PressCard(modifier.fillMaxWidth()) {
        Column {
            SectionTitle("实时指标") { T("每秒刷新", 13.sp, color = C.Muted) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val a = rememberLiveSeries(base = 42f, amp = 10f, seed = 7)
                val b = rememberLiveSeries(base = 30f, amp = 8f, seed = 11)
                MetricTile(Ic.ArrowUp, "触发频率", a.points.last().toInt().toString(), "次/分", "累计 12,480 次", a, Modifier.weight(1f))
                MetricTile(Ic.ArrowDown, "执行延迟", b.points.last().toInt().toString(), "ms", "平均 31 ms", b, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricTile(
    ic: Ic, label: String, value: String, unit: String, total: String,
    series: LiveSeries, modifier: Modifier,
) {
    Box(modifier.height(168.dp).clip(RoundedRectangle(24.dp)).background(C.Tonal)) {
        Sparkline(series, C.Primary, Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter))
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineIcon(ic, C.Primary, 16.dp)
                Spacer(Modifier.width(4.dp))
                T(label, 13.sp, FontWeight.Medium, C.OnSurfaceVar)
            }
            Spacer(Modifier.height(6.dp))
            BigValue(value, unit, 36.sp)
            T(total, 12.sp, color = C.Muted)
        }
    }
}

@Composable
private fun ModeCard(state: AppState, modifier: Modifier) {
    PressCard(modifier.fillMaxWidth()) {
        Column {
            SectionTitle("运行模式") {
                AnimatedContent(
                    state.mode,
                    transitionSpec = { fadeIn(effect()) togetherWith fadeOut(effect()) },
                    label = "modeHint"
                ) { T(listOf("轮询 60 秒", "轮询 15 秒", "实时监听")[it], 13.sp, color = C.Muted) }
            }
            Spacer(Modifier.height(14.dp))
            SegmentedSelector(
                listOf(Ic.Leaf to "省电", Ic.Gauge to "均衡", Ic.Rocket to "极速"),
                state.mode
            ) { state.mode = it }
        }
    }
}

