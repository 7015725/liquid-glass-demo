package com.flowglass.demo.system

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.flowglass.demo.MainActivity

/** Bumped on every Activity resume so Compose re-reads system states. */
object SysTick {
    var tick by mutableIntStateOf(0)
}

/**
 * Standard, policy-friendly keep-alive measures (inspired by the strategy list in
 * Pangu-Immortal/KeepLiveService): foreground service, boot start, periodic job,
 * battery-optimization exemption and vendor auto-start settings.
 */
object KeepAlive {
    private const val PREFS = "keepalive"
    const val KEY_FGS = "fgs"
    const val KEY_BOOT = "boot"
    const val KEY_JOB = "job"
    private const val JOB_ID = 7015

    @Volatile var serviceRunning = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isOn(ctx: Context, key: String) = prefs(ctx).getBoolean(key, false)

    fun set(ctx: Context, key: String, on: Boolean) {
        prefs(ctx).edit().putBoolean(key, on).apply()
        when (key) {
            KEY_FGS -> if (on) startService(ctx) else stopService(ctx)
            KEY_JOB -> if (on) scheduleJob(ctx) else cancelJob(ctx)
        }
    }

    fun startService(ctx: Context) {
        try {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Throwable) {
        }
    }

    fun stopService(ctx: Context) {
        ctx.stopService(Intent(ctx, KeepAliveService::class.java))
    }

    fun ensure(ctx: Context) {
        if (isOn(ctx, KEY_FGS) && !serviceRunning) startService(ctx)
        if (isOn(ctx, KEY_JOB) && !isJobScheduled(ctx)) scheduleJob(ctx)
    }

    fun scheduleJob(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, KeepAliveJob::class.java))
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build()
        try { js.schedule(job) } catch (_: Throwable) {}
    }

    fun cancelJob(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    fun isJobScheduled(ctx: Context): Boolean =
        ctx.getSystemService(JobScheduler::class.java)?.allPendingJobs?.any { it.id == JOB_ID } == true

    fun isIgnoringBattery(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    @SuppressLint("BatteryLife")
    fun batteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    private val vendorAutoStart = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
    )

    /** Opens the ROM's auto-start manager if one is found, else the app details page. */
    fun openAutoStart(ctx: Context) {
        for ((pkg, cls) in vendorAutoStart) {
            val i = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(i); return
            } catch (_: Throwable) {
            }
        }
        openAppDetails(ctx)
    }

    fun openAppDetails(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    fun vendorName(): String = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> "小米 / HyperOS"
        "huawei", "honor" -> "华为 / 荣耀"
        "oppo", "realme", "oneplus" -> "OPPO / 一加 / realme"
        "vivo", "iqoo" -> "vivo / iQOO"
        "samsung" -> "三星"
        "meizu" -> "魅族"
        else -> Build.MANUFACTURER
    }
}

class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("keepalive", "后台常驻", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, "keepalive")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("流光自动化 · 指令编写器")
            .setContentText("仅编写、校验和保存，不监听或执行指令")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, n)
            }
            KeepAlive.serviceRunning = true
        } catch (_: Throwable) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        KeepAlive.serviceRunning = false
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (KeepAlive.isOn(context, KeepAlive.KEY_BOOT)) KeepAlive.ensure(context)
    }
}

class KeepAliveJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (KeepAlive.isOn(this, KeepAlive.KEY_FGS) && !KeepAlive.serviceRunning) KeepAlive.startService(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
