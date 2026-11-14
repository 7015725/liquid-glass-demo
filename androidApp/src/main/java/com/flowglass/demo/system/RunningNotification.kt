package com.flowglass.demo.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.flowglass.demo.MainActivity
import java.util.Collections

/**
 * One foreground notification for the whole app.
 *
 * Trigger listening and keep-alive are two services with different lifetimes, but users were
 * seeing two identical "流光自动化正在运行" notifications. Both services now start foreground with
 * the same id on one channel; the text is composed from both states, and stopping one service
 * keeps the notification while the other one still runs.
 */
object RunningNotification {
    const val ID = 7016
    private const val CHANNEL = "running"
    const val OWNER_TRIGGER = "trigger"
    const val OWNER_KEEPALIVE = "keepalive"

    @Volatile private var triggerCount = 0
    private val owners: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "自动指令监听与后台保活"
                setShowBadge(false)
            },
        )
    }

    /** Called by the trigger host whenever the listening rule count changes. */
    fun onTriggerStatus(ctx: Context, count: Int) {
        triggerCount = count
        refresh(ctx)
    }

    fun start(service: Service, owner: String) {
        ensureChannel(service)
        owners.add(owner)
        try {
            val notification = build(service)
            if (Build.VERSION.SDK_INT >= 34) {
                service.startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                service.startForeground(ID, notification)
            }
        } catch (_: Throwable) {
            owners.remove(owner)
            service.stopSelf()
        }
    }

    fun stop(service: Service, owner: String) {
        owners.remove(owner)
        try {
            service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        } catch (_: Throwable) {
        }
        if (owners.isEmpty()) {
            runCatching { service.getSystemService(NotificationManager::class.java).cancel(ID) }
        } else {
            refresh(service)
        }
    }

    fun refresh(ctx: Context) {
        if (owners.isEmpty()) return
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx)) }
    }

    fun build(ctx: Context): Notification {
        val triggerActive = owners.contains(OWNER_TRIGGER) || com.flowglass.demo.system.trigger.TriggerStatus.serviceActive
        val keepAlive = owners.contains(OWNER_KEEPALIVE) || KeepAlive.serviceRunning
        val text = buildString {
            when {
                triggerActive && triggerCount > 0 -> append("正在监听 $triggerCount 条自动指令")
                triggerActive -> append("自动指令监听已就绪")
                else -> append("自动指令保持监听中")
            }
            if (keepAlive) append(" · 后台保活已开启")
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("流光自动化正在运行")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }
}
