package com.flowglass.demo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlin.random.Random

// ================================================================ Tasks (grid selection)

data class TaskItem(val id: Int, val ic: Ic, val title: String, val type: String, val category: Int)

val Categories = listOf("全部", "定时", "通知", "位置", "设备", "网络")

val Tasks = listOf(
    TaskItem(0, Ic.Timer, "每日 7:00 唤醒", "定时", 1),
    TaskItem(1, Ic.Moon, "夜间自动静音", "定时", 1),
    TaskItem(2, Ic.Bell, "验证码自动复制", "通知", 2),
    TaskItem(3, Ic.Message, "快递短信归档", "通知", 2),
    TaskItem(4, Ic.Location, "到公司开 WiFi", "位置", 3),
    TaskItem(5, Ic.Home, "到家连蓝牙音箱", "位置", 3),
    TaskItem(6, Ic.Battery, "低电量省电模式", "设备", 4),
    TaskItem(7, Ic.Camera, "截图自动上传", "设备", 4),
    TaskItem(8, Ic.Wifi, "断网自动重连", "网络", 5),
    TaskItem(9, Ic.Bluetooth, "耳机连上放音乐", "设备", 4),
    TaskItem(10, Ic.Bolt, "充电时清理后台", "设备", 4),
    TaskItem(11, Ic.Shield, "陌生来电拦截", "通知", 2),
    TaskItem(12, Ic.Clock, "整点报时", "定时", 1),
    TaskItem(13, Ic.Phone, "翻转手机免打扰", "设备", 4),
    TaskItem(14, Ic.Search, "网速异常提醒", "网络", 5),
    TaskItem(15, Ic.Refresh, "定时同步相册", "网络", 5),
    TaskItem(16, Ic.Leaf, "周末减少通知", "定时", 1),
    TaskItem(17, Ic.Gauge, "高温降频提醒", "设备", 4),
)

@Composable
fun TasksScreen(state: AppState) {
    val list = Tasks.filter { state.category == 0 || it.category == state.category }
    FilterChips(Categories, state.category, { state.category = it }, Modifier.staggerIn(0))
    Row(Modifier.fillMaxWidth().staggerIn(1), verticalAlignment = Alignment.CenterVertically) {
        T("已选 ${state.selectedTasks.size} 项", 14.sp, FontWeight.Medium, C.OnSurfaceVar, Modifier.weight(1f))
        Row(
            Modifier.clip(Capsule()).background(C.Card).pressable { state.testRun += 1 }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LineIcon(Ic.Refresh, C.Primary, 18.dp)
            Spacer(Modifier.width(6.dp))
            T("批量检测", 14.sp, FontWeight.SemiBold, C.Primary)
        }
    }
    key(state.category) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { c, task ->
                        val idx = r * 3 + c
                        TaskCard(task, state, idx, Modifier.weight(1f).staggerIn(idx + 2))
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskItem, state: AppState, index: Int, modifier: Modifier) {
    val selected = task.id in state.selectedTasks
    var loading by remember { mutableStateOf(false) }
    var latency by remember { mutableIntStateOf(20 + (task.id * 37) % 140) }
    LaunchedEffect(state.testRun) {
        if (state.testRun > 0) {
            loading = true
            delay(index * 90L + 500L)
            latency = Random.nextInt(12, 260)
            loading = false
        }
    }
    val quality = when {
        latency < 80 -> C.Good
        latency < 160 -> C.Mid
        else -> C.Bad
    }
    val valueColor by animateColorAsState(quality, effect(), label = "latency")
    PressCard(
        modifier.height(128.dp),
        foreground = Modifier.shimmer(loading),
        radius = 24.dp,
        contentPadding = 12.dp,
        onClick = {
            state.selectedTasks = if (selected) state.selectedTasks - task.id else state.selectedTasks + task.id
        }
    ) {
        Box(Modifier.height(104.dp).fillMaxWidth()) {
            Column {
                LineIcon(task.ic, C.OnSurface, 22.dp)
                Spacer(Modifier.height(8.dp))
                T(task.title, 14.sp, FontWeight.Bold, maxLines = 2, lineHeight = 18.sp)
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                T(task.type, 12.sp, color = C.Muted, modifier = Modifier.weight(1f))
                AnimatedVisibility(!loading, enter = fadeIn(effect()), exit = fadeOut(effect())) {
                    T("${latency}ms", 12.sp, FontWeight.Bold, valueColor, tabular = true)
                }
            }
            CheckBadge(selected, 22.dp, Modifier.align(Alignment.TopEnd))
        }
    }
}

