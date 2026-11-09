package com.flowglass.demo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun HomeScreen(state: AppState) {
    var i = 0
    HeroCard(state, Modifier.staggerIn(i++))
    MetricsCard(state, Modifier.staggerIn(i++))
    ModeCard(state, Modifier.staggerIn(i++))
    DeviceCard(state, Modifier.staggerIn(i++))
}

@Composable
private fun HeroCard(state: AppState, modifier: Modifier) {
    // uptime ticks while running
    LaunchedEffect(state.running) {
        while (isActive && state.running) {
            delay(1000)
            state.uptime += 1
            if (state.uptime % 3 == 0) state.triggers += 1
        }
    }
    val h = state.uptime / 3600
    val m = (state.uptime / 60) % 60
    val s = state.uptime % 60
    val uptime = "%d:%02d:%02d".format(h, m, s)

    PressCard(
        modifier.fillMaxWidth(),
        background = {
            Box(Modifier.matchParentSize()) {
                Blob(
                    C.Primary.copy(alpha = 0.13f),
                    Modifier.size(230.dp).align(Alignment.CenterEnd).offset(x = 70.dp, y = 30.dp),
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
                        ) { LineIcon(Ic.Shield, C.Primary, 20.dp) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            T("夜间守护", 14.sp, FontWeight.SemiBold)
                            T("已持续运行", 12.sp, color = C.Muted)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    RollingText(uptime, 38.sp, FontWeight.Bold, if (state.running) C.OnSurface else C.Muted)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TonalChip("6 条规则")
                        TonalChip("省心")
                    }
                }
                ScallopButton(state.running, { state.running = !state.running })
            }
            Spacer(Modifier.height(20.dp))
            Row {
                Meta("今日触发", "%,d".format(state.triggers), Modifier.weight(1f))
                Meta("平均响应", "32 ms", Modifier.weight(1f))
            }
        }
    }
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

@Composable
private fun DeviceCard(state: AppState, modifier: Modifier) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    PressCard(modifier.fillMaxWidth()) {
        Column {
            SectionTitle("设备标识") {
                AnimatedContent(copied, transitionSpec = { fadeIn(effect()) togetherWith fadeOut(effect()) }, label = "copied") {
                    if (it) T("已复制", 13.sp, FontWeight.Medium, C.Primary, Modifier.padding(end = 8.dp))
                }
                CircleIconButton(Ic.Copy, { copied = true })
            }
            Spacer(Modifier.height(4.dp))
            T("FG-7015-A3", 40.sp, FontWeight.Bold, tabular = true)
            Spacer(Modifier.height(14.dp))

            // The glass toggle refracts this row, so the row is its own backdrop.
            val rowBackdrop = rememberLayerBackdrop()
            Box(Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp))) {
                Box(Modifier.matchParentSize().layerBackdrop(rowBackdrop).background(C.Tonal))
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(Capsule()).background(androidx.compose.ui.graphics.Color.White),
                        contentAlignment = Alignment.Center
                    ) { LineIcon(Ic.Accessibility, C.OnSurface, 22.dp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        T("无障碍服务", 15.sp, FontWeight.SemiBold)
                        AnimatedContent(state.accessibility, transitionSpec = { fadeIn(effect()) togetherWith fadeOut(effect()) }, label = "acc") {
                            T(if (it) "已开启 · 监听 6 个应用" else "已关闭", 12.sp, color = if (it) C.GoodText else C.Muted)
                        }
                    }
                    LiquidToggle({ state.accessibility }, { state.accessibility = it }, rowBackdrop)
                }
            }
        }
    }
}
