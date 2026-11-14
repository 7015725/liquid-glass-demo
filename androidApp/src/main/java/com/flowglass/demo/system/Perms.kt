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
            { c -> (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                c.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == true },
            if (Build.VERSION.SDK_INT >= 33) Grant.Runtime(Manifest.permission.POST_NOTIFICATIONS)
            else Grant.Page { c -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName) }
        ))
        add(PermItem(
            "write_settings", Ic.Gauge, "修改系统设置",
            "调节屏幕亮度并切换手动亮度模式", false,
            { c -> Settings.System.canWrite(c) },
            Grant.Page { c -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(c)) }
        ))
        add(PermItem(
            "dnd", Ic.Moon, "勿扰权限",
            "开启、关闭或切换勿扰模式", false,
            { c -> c.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true },
            Grant.Page { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
        ))
        if (Build.VERSION.SDK_INT >= 31) add(PermItem(
            "bluetooth_connect", Ic.Bluetooth, "蓝牙连接",
            "切换蓝牙时读取当前开关状态；自动切换仍需要 Root", false,
            { c -> c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED },
            Grant.Runtime(Manifest.permission.BLUETOOTH_CONNECT)
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
            "notification_listener", Ic.Bell, "通知使用权",
            "读取新通知的标题与内容，触发通知相关指令", false,
            { c -> isNotificationListenerEnabled(c) },
            Grant.Page { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        ))
        add(PermItem(
            "foreground_app", Ic.Grid, "前台应用",
            "感知应用切换；优先使用无障碍服务，其次使用情况访问", true,
            { c -> isA11yEnabled(c) || hasUsageAccess(c) },
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

    fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners",
        ) ?: return false
        val me = ComponentName(ctx, com.flowglass.demo.system.trigger.FlowNotificationListener::class.java)
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
    @Volatile private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastPackage = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        val change = event ?: return
        if (change.eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = change.packageName?.toString() ?: return
        if (packageName == lastPackage) return
        lastPackage = packageName
        foregroundListeners.forEach { listener ->
            runCatching { listener(packageName) }
        }
    }

    override fun onInterrupt() {}

    fun perform(action: Int): Boolean = performGlobalAction(action)

    companion object {
        @Volatile
        var instance: FlowAccessibilityService? = null
            private set

        private val foregroundListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

        fun addForegroundListener(listener: (String) -> Unit) {
            foregroundListeners.addIfAbsent(listener)
        }

        fun removeForegroundListener(listener: (String) -> Unit) {
            foregroundListeners.remove(listener)
        }
    }
}
