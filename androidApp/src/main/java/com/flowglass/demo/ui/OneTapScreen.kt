package com.flowglass.demo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class OneTap(val ic: Ic, val title: String, val sub: String, val time: String)

val OneTaps = listOf(
    OneTap(Ic.Leaf, "一键省电", "关闭 5G / 降低亮度 / 限制后台", "上次 08:12"),
    OneTap(Ic.Moon, "一键勿扰", "静音 + 屏蔽通知 2 小时", "上次 昨天"),
    OneTap(Ic.Rocket, "清理后台", "结束 12 个后台应用", "上次 07:40"),
    OneTap(Ic.Wifi, "开启热点", "开热点并共享给常用设备", "上次 周日"),
    OneTap(Ic.Location, "发送定位", "把当前位置发给家人", "从未"),
    OneTap(Ic.Camera, "扫码付款", "直接打开付款码", "上次 12:31"),
    OneTap(Ic.Bluetooth, "连接耳机", "切换到蓝牙耳机并播放", "上次 18:05"),
    OneTap(Ic.Terminal, "执行脚本", "运行自定义 Shell（需 Root）", "上次 3 天前"),
)

@Composable
fun OneTapScreen(state: AppState) {
    LaunchedEffect(state.runSeq) {
        if (state.runSeq > 0) {
            delay(1800)
            state.runningTap = -1
        }
    }
    OneTaps.forEachIndexed { i, cmd ->
        val done = i == state.lastRun
        val busy = i == state.runningTap
        PressCard(
            Modifier.fillMaxWidth().staggerIn(i),
            radius = 26.dp,
            contentPadding = 16.dp,
            foreground = Modifier.shimmer(busy),
            onClick = {
                state.lastRun = i
                state.runningTap = i
                state.runSeq++
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(PolygonShape(M3EShapes.Clover4))
                        .background(if (done) C.PrimarySoft else C.Tonal),
                    contentAlignment = Alignment.Center
                ) { LineIcon(cmd.ic, if (done) C.Primary else C.OnSurfaceVar, 22.dp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    T(cmd.title, 17.sp, FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    T(cmd.sub, 13.sp, color = C.Muted, maxLines = 1)
                }
                AnimatedContent(
                    if (done && !busy) "刚刚" else cmd.time,
                    transitionSpec = { fadeIn(effect()) togetherWith fadeOut(effect()) },
                    label = "time"
                ) { T(it, 13.sp, color = if (it == "刚刚") C.Primary else C.Muted) }
                AnimatedVisibility(done, enter = fadeIn(effect()), exit = fadeOut(effect())) {
                    Row {
                        Spacer(Modifier.width(10.dp))
                        CheckBadge(done && !busy, 24.dp)
                    }
                }
            }
        }
    }
}
