package com.flowglass.demo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.flowglass.demo.system.Grant
import com.flowglass.demo.system.PermItem
import com.flowglass.demo.system.Perms
import com.flowglass.demo.system.trigger.missingPermissionItems
import com.kyant.shapes.RoundedRectangle

/**
 * Enabling an automatic command is the last safe moment to check the permissions of all of its
 * triggers, conditions and actions. The catalog owns the required keys; this host only renders
 * whatever the enabled rule asks for, so new components do not need UI changes.
 */
internal object PermissionGate {
    data class Pending(val cmdId: Long)

    var pending by mutableStateOf<Pending?>(null)
        private set

    fun request(state: AppState, cmd: Cmd, context: android.content.Context) {
        if (cmd.enabled) {
            state.setEnabled(cmd.id, false)
            return
        }
        val missing = cmd.missingPermissionItems(context)
        if (missing.isEmpty()) {
            state.setEnabled(cmd.id, true)
            state.permissionsEpoch += 1
            com.flowglass.demo.system.trigger.TriggerService.ensureStarted(context)
        } else {
            pending = Pending(cmd.id)
        }
    }

    fun dismiss() {
        pending = null
    }
}

@Composable
internal fun PermissionResumeEffect(state: AppState) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(owner, state) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && state.running) {
                state.permissionsEpoch += 1
                com.flowglass.demo.system.trigger.TriggerService.ensureStarted(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
internal fun PermissionPromptHost(state: AppState) {
    val request = PermissionGate.pending ?: return
    val context = LocalContext.current
    val cmd = state.find(request.cmdId)
    if (cmd == null) {
        PermissionGate.dismiss()
        return
    }
    var missing by remember(cmd.id) { mutableStateOf(cmd.missingPermissionItems(context)) }
    fun refresh() {
        missing = cmd.missingPermissionItems(context)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }
    val view = LocalView.current
    DisposableEffect(view, cmd.id) {
        val owner = view.findViewTreeLifecycleOwner()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(missing, cmd.id) {
        if (missing.isEmpty()) {
            state.setEnabled(cmd.id, true)
            state.permissionsEpoch += 1
            com.flowglass.demo.system.trigger.TriggerService.ensureStarted(context)
            PermissionGate.dismiss()
        }
    }

    Dialog(
        onDismissRequest = { PermissionGate.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 24.dp).testTag("permission-prompt"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedRectangle(28.dp))
                    .background(C.Card)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                T("开启「${cmd.title}」前需要授权", 19.sp, FontWeight.Bold)
                T("缺少权限时不会注册监听；授权后会自动开启。", 13.sp, color = C.OnSurfaceVar)
                if (missing.isEmpty()) {
                    T("正在刷新权限状态…", 13.sp, color = C.Muted)
                }
                missing.forEach { permission ->
                    PermissionRow(permission, launcher)
                }
                Box(
                    Modifier.fillMaxWidth().padding(top = 4.dp)
                        .clip(CapsuleShape).background(C.Tonal).pressable {
                            PermissionGate.dismiss()
                        }
                        .padding(vertical = 12.dp).testTag("permission-cancel"),
                    contentAlignment = Alignment.Center,
                ) {
                    T("暂不开启", 14.sp, FontWeight.Medium, C.OnSurfaceVar)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    permission: PermItem,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedRectangle(20.dp))
            .background(C.Tonal)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LineIcon(permission.ic, C.Primary, 20.dp)
        Column(Modifier.weight(1f)) {
            T(permission.name, 14.sp, FontWeight.SemiBold)
            T(permission.purpose, 12.sp, color = C.OnSurfaceVar)
        }
        Box(
            Modifier.clip(CapsuleShape).background(C.PrimarySoft)
                .pressable {
                    when (val grant = permission.grant) {
                        is Grant.Runtime -> launcher.launch(grant.permission)
                        is Grant.Page -> Perms.open(context, grant.intent(context))
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("permission-grant-${permission.key}"),
            contentAlignment = Alignment.Center,
        ) {
            T("去授权", 13.sp, FontWeight.SemiBold, C.Primary)
        }
    }
}

private val CapsuleShape = com.kyant.shapes.Capsule()
