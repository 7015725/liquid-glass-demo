package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.engine.*
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun HomeScreen(state: AppState, onOpenTab: (Int) -> Unit) {
    PressCard(Modifier.fillMaxWidth(), background = {
        Box(Modifier.matchParentSize()) {
            Blob(C.Primary.copy(alpha = 0.12f), Modifier.size(220.dp).align(Alignment.CenterEnd).offset(x = 70.dp), seed = 3)
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineIcon(Ic.Bolt, C.Primary, 24.dp)
                Spacer(Modifier.width(10.dp))
                T("指令编写器", 22.sp, FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                StatusPill("本地", false)
            }
            T("把想做的事，写成一条指令。", 16.sp, FontWeight.Medium)
            T("触发器 → 条件 → 动作\n当前版本负责编辑、校验与保存；不监听事件，不执行 Shell、脚本或特权动作。", 13.sp, color = C.OnSurfaceVar, lineHeight = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeStat("自动指令", state.autoCmds.size.toString(), Modifier.weight(1f)) { onOpenTab(1) }
                HomeStat("一键指令", state.oneTaps.size.toString(), Modifier.weight(1f)) { onOpenTab(2) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuthoringButton("新建自动指令", "home-create-auto", Modifier.weight(1f)) { state.create(true) }
                AuthoringButton("新建一键指令", "home-create-manual", Modifier.weight(1f), primary = false) { state.create(false) }
            }
        }
    }
    PressCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("从模板开始") { T("保存后才入库", 12.sp, color = C.OnSurfaceVar) }
            T("模板仅用于编写示范，始终保持停用。", 13.sp, color = C.OnSurfaceVar)
            AuthoringButton("连接 WiFi → 发送通知", "template-wifi", primary = false) {
                state.editor = Rule(name = "到家提醒", description = "连接指定 WiFi 后发送一条通知",
                    triggers = listOf(ComponentRegistry.newComponent("trigger.wifi_connected").copy(params = mapOf("ssid" to JsonPrimitive("Home")))),
                    actions = listOf(ComponentRegistry.newComponent("action.notification").copy(params = mapOf("title" to JsonPrimitive("到家了"), "text" to JsonPrimitive("已连接 {event.ssid}")))))
            }
            AuthoringButton("一键指令 → 延时与通知", "template-manual", primary = false) {
                state.editor = Rule(name = "稍后提醒", type = "manual", actions = listOf(
                    ComponentRegistry.newComponent("action.delay"),
                    ComponentRegistry.newComponent("action.notification").copy(params = mapOf("title" to JsonPrimitive("提醒"), "text" to JsonPrimitive("该休息一下了")))))
            }
        }
    }
    PressCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("规则资料库")
            T("全局变量 ${state.globals.size} 项 · 格式版本 1\nJSON 导出包含变量值，请只分享给信任的人。", 13.sp, color = C.OnSurfaceVar)
            LibraryTools(state)
        }
    }
    if (state.rules.isNotEmpty()) {
        SectionTitle("继续编写")
        state.rules.sortedBy { it.name }.take(3).forEach { rule ->
            PressCard(Modifier.fillMaxWidth(), onClick = { state.edit(rule.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(rule.name, 16.sp, FontWeight.SemiBold)
                        T(Cmd(rule).meta, 12.sp, color = C.OnSurfaceVar)
                    }
                    AuthoringButton("编辑", "home-edit-${rule.id}", primary = false) { state.edit(rule.id) }
                }
            }
        }
    }
}

@Composable
private fun HomeStat(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedRectangle(24.dp)).background(C.Tonal).clickable(onClick = onClick).padding(16.dp)) {
        T(label, 13.sp, color = C.OnSurfaceVar)
        T(value, 36.sp, FontWeight.Bold)
        T("已保存", 12.sp, color = C.OnSurfaceVar)
    }
}

@Composable
fun AuthoringButton(text: String, tag: String, modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(Capsule()).background(if (primary) C.Primary else C.Tonal)
        .testTag(tag).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        T(text, 14.sp, FontWeight.SemiBold, if (primary) Color.White else C.OnSurfaceVar)
    }
}

@Composable
fun LibraryTools(state: AppState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AuthoringButton("导入 JSON", "library-import", Modifier.weight(1f), false) { state.importRequested++ }
        AuthoringButton("导出全部", "library-export", Modifier.weight(1f), false) { state.requestExport() }
    }
    AuthoringButton("全局变量", "library-globals", Modifier.fillMaxWidth(), false) { state.showGlobals = true }
}
