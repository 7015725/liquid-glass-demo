package com.flowglass.demo.system

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.flowglass.demo.ui.Ic

/** How a permission is granted: a runtime dialog, or a system settings page. */
sealed interface Grant {
    data class Runtime(val permission: String) : Grant
    data class Page(val intent: (Context) -> Intent) : Grant
}

data class PermItem(
    val key: String,
    val ic: Ic,
    val name: String,
    val purpose: String,
    val required: Boolean,
    val check: (Context) -> Boolean,
    val grant: Grant,
)

object Perms {
    private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    val all: List<PermItem> = buildList {
        add(PermItem(
            "notify", Ic.Bell, "通知",
            "显示常驻通知、指令执行结果提醒", true,
            { c -> Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
            if (Build.VERSION.SDK_INT >= 33) Grant.Runtime(Manifest.permission.POST_NOTIFICATIONS)
            else Grant.Page { c -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName) }
        ))
        add(PermItem(
            "battery", Ic.Battery, "忽略电池优化",
            "防止系统省电策略冻结后台服务", true,
            { c -> KeepAlive.isIgnoringBattery(c) },
            Grant.Page { c -> KeepAlive.batteryIntent(c) }
        ))
        add(PermItem(
            "a11y", Ic.Accessibility, "无障碍服务",
            "识别界面元素，执行自动点击与滑动", true,
            { c -> isA11yEnabled(c) },
            Grant.Page { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        ))
        add(PermItem(
            "overlay", Ic.Grid, "悬浮窗",
            "显示悬浮控制球与执行进度", false,
            { c -> Settings.canDrawOverlays(c) },
            Grant.Page { c -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(c)) }
        ))
        add(PermItem(
            "usage", Ic.Gauge, "使用情况访问",
            "感知前台应用切换，作为指令触发条件", false,
            { c -> hasUsageAccess(c) },
            Grant.Page { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        ))
        add(PermItem(
            "alarm", Ic.Timer, "精确闹钟",
            "让定时指令准点触发", false,
            { c -> Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() },
            Grant.Page { c ->
                if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(c))
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c))
            }
        ))
        add(PermItem(
            "location", Ic.Location, "位置信息",
            "到达 / 离开某地时触发指令", false,
            { c -> c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED },
            Grant.Runtime(Manifest.permission.ACCESS_FINE_LOCATION)
        ))
    }

    fun isA11yEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, FlowAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    @Suppress("DEPRECATION")
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun open(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            KeepAlive.openAppDetails(ctx)
        }
    }
}

class FlowAccessibilityService : android.accessibilityservice.AccessibilityService() {
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
