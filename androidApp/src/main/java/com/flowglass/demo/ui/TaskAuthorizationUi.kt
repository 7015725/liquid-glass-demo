package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.system.Perms
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.SysTick
import com.kyant.shapes.Capsule

internal val RootTaskNames = setOf("执行 Shell(Root)", "开关 WiFi", "开关蓝牙")

@Composable
internal fun RefreshEditorRoot(state: AppState, cmd: Cmd) {
    val tick = SysTick.tick
    val needed = cmd.actions.any { it.name in RootTaskNames }
    LaunchedEffect(tick, needed) {
        // Passive cached/probe status only. Rendering a label must never request su authorization.
        if (needed) state.root = Root.check(request = false)
    }
}

@Composable
internal fun TaskAuthorizationBadge(state: AppState, section: CommandSection, item: CommandItem, onAuthorization: (Int) -> Unit) {
    if (section != CommandSection.ACTIONS) return
    val context = LocalContext.current
    val tick = SysTick.tick
    val permissionKey = when (item.name) {
        "发送通知" -> "notify"
        "开关勿扰" -> "dnd"
        "调节亮度" -> "write_settings"
        "开关蓝牙" -> if (android.os.Build.VERSION.SDK_INT >= 31 &&
            (item.parameter.trim() == "切换" || item.parameter.trim().equals("toggle", true))) "bluetooth_connect" else null
        else -> null
    }
    val permissionMissing = remember(context, tick, permissionKey) {
        permissionKey != null && Perms.all.first { it.key == permissionKey }.check(context).not()
    }
    val destination = when {
        permissionMissing -> SettingsPage.Permissions
        item.name in RootTaskNames && state.root?.granted != true -> SettingsPage.Root
        else -> null
    } ?: return
    Box(Modifier.heightIn(min = 48.dp).testTag("authorization-${item.id}")
        .clip(Capsule()).background(Color(0xFFFFE7A0))
        .editorAction("${item.name}需授权") { onAuthorization(destination) }
        .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        T("需授权", 13.sp, FontWeight.SemiBold, Color(0xFF795500))
    }
}
