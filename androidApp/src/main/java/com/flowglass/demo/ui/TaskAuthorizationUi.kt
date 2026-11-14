package com.flowglass.demo.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.flowglass.demo.system.PermItem
import com.flowglass.demo.system.Perms
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.SysTick
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

internal val RootTaskNames = setOf("执行 Shell(Root)", "开关 WiFi", "开关蓝牙")

private fun bluetoothToggle(parameter: String): Boolean {
    val value = parameter.trim()
    return value == "切换" || value.equals("toggle", true)
}

/**
 * The permission a step depends on. Metadata is the source of truth; the name table only keeps
 * rules from earlier builds (typeId == null) working. Bluetooth on/off goes through Root, so only
 * the "切换" mode asks for the runtime permission.
 */
internal fun CommandItem.permissionKey(): String? {
    val meta = typeId?.let { CommandCatalog.meta(it) }
    if (meta != null) {
        val declared = meta.permissions.firstOrNull { key -> Perms.all.any { it.key == key } }
        if (declared == "bluetooth_connect" && !bluetoothToggle(parameter)) return null
        return declared
    }
    return when (name) {
        "发送通知" -> "notify"
        "开关勿扰" -> "dnd"
        "调节亮度" -> "write_settings"
        "开关蓝牙" -> if (bluetoothToggle(parameter)) "bluetooth_connect" else null
        else -> null
    }
}

/** Root is required when metadata says so, or for the shell/WiFi/Bluetooth names of old rules. */
internal fun CommandItem.requiresRoot(): Boolean =
    typeId?.let { CommandCatalog.meta(it)?.minTier == com.flowglass.engine.Tier.ROOT } ?: (name in RootTaskNames)

/** Every permission the rule needs and the app does not hold right now. */
internal fun Cmd.missingPermissions(ctx: Context): List<PermItem> =
    (triggers + conditions + actions)
        .mapNotNull { it.permissionKey() }
        .distinct()
        .mapNotNull { key -> Perms.all.firstOrNull { it.key == key }?.takeIf { !it.check(ctx) } }

@Composable
internal fun RefreshEditorRoot(state: AppState, cmd: Cmd) {
    val tick = SysTick.tick
    val needed = cmd.actions.any { it.requiresRoot() }
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
    val permissionKey = item.permissionKey()
    val permissionMissing = remember(context, tick, permissionKey) {
        permissionKey != null && Perms.all.first { it.key == permissionKey }.check(context).not()
    }
    val destination = when {
        permissionMissing -> SettingsPage.Permissions
        item.requiresRoot() && state.root?.granted != true -> SettingsPage.Root
        else -> null
    } ?: return
    Box(Modifier.heightIn(min = 48.dp).testTag("authorization-${item.id}")
        .clip(com.kyant.shapes.Capsule()).background(C.WarnSoft)
        .editorAction("${item.name}需授权") { onAuthorization(destination) }
        .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        T("需授权", 13.sp, FontWeight.SemiBold, C.WarnText)
    }
}

/**
 * Editor-level summary of the permissions this exact rule needs, using the same [Perms] checks
 * as the per-step badges. Tapping it opens the existing permissions page; the enable-time
 * checklist dialog belongs to another branch.
 */
@Composable
internal fun MissingPermissionNotice(cmd: Cmd, onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val tick = SysTick.tick
    val missing = remember(cmd, tick, context) { cmd.missingPermissions(context) }
    if (missing.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().testTag("editor-missing-permission")
            .clip(RoundedRectangle(24.dp)).background(C.WarnSoft)
            .editorAction("缺少权限，去授权") { onOpenPermissions() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineIcon(Ic.Shield, C.WarnText, 18.dp)
            Spacer(Modifier.width(8.dp))
            T("缺少权限", 14.sp, FontWeight.SemiBold, C.WarnText, modifier = Modifier.weight(1f))
            T("去授权", 13.sp, FontWeight.Bold, C.WarnText)
        }
        Spacer(Modifier.height(6.dp))
        T(
            missing.joinToString("、") { it.name } + " 未授权，相关步骤会失败或跳过；授权后立即生效。",
            12.sp, color = C.WarnText, lineHeight = 18.sp,
        )
    }
}
