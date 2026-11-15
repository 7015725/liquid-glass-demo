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
import android.util.Log
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
    private const val TAG = "RunningNotification"

    @Volatile private var triggerCount = 0
    private val owners: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val typeStates: MutableMap<String, ForegroundTypeState> = Collections.synchronizedMap(mutableMapOf())

    private val requiresLocationType: Boolean
        get() = Build.VERSION.SDK_INT >= 34

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "自动指令监听与后台保活"
                setShowBadge(false)
            },
        )
    }

    /** Removes channels left by pre-0.6.1 builds; upgrades keep them until explicitly deleted. */
    fun cleanupLegacyChannels(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        listOf("keepalive", "triggers").forEach { id ->
            runCatching { manager.deleteNotificationChannel(id) }
        }
        ensureChannel(ctx)
    }

    /** Called by the trigger host whenever the listening rule count changes. */
    fun onTriggerStatus(ctx: Context, count: Int) {
        triggerCount = count
        refreshLocationStatus()
        refresh(ctx)
    }

    fun start(service: Service, owner: String, includeLocation: Boolean = false) {
        ensureChannel(service)
        owners.add(owner)
        val state = typeStates.getOrPut(owner) { ForegroundTypeState() }
        try {
            if (!requiresLocationType) {
                // API < 34 has no foreground-service type mask; keep the legacy call.
                service.startForeground(ID, build(service))
            } else {
                val action = state.next(includeLocation)
                if (action != ForegroundStartAction.NONE) {
                    val notification = build(service)
                    if (action == ForegroundStartAction.LOCATION) {
                        try {
                            service.startForeground(
                                ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                            )
                            state.onStarted(action)
                        } catch (failure: Throwable) {
                            // A background start cannot claim the location type without
                            // ACCESS_BACKGROUND_LOCATION. Never replace an existing
                            // foreground type with a weaker one; only fall back when this
                            // service has not started foreground at all yet.
                            Log.w(TAG, "location foreground type rejected; keeping current type", failure)
                            state.onLocationFailure()
                            if (state.currentTypes == 0) {
                                service.startForeground(
                                    ID,
                                    notification,
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                                )
                                state.onStarted(ForegroundStartAction.SPECIAL_USE)
                            }
                        }
                    } else {
                        service.startForeground(
                            ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                        )
                        state.onStarted(action)
                    }
                }
            }
            refreshLocationStatus()
            refresh(service)
        } catch (_: Throwable) {
            owners.remove(owner)
            typeStates.remove(owner)
            service.stopSelf()
        }
    }

    fun promote(service: Service, owner: String, includeLocation: Boolean = false) {
        if (!owners.contains(owner)) return
        start(service, owner, includeLocation)
    }

    fun stop(service: Service, owner: String) {
        owners.remove(owner)
        typeStates.remove(owner)
        try {
            service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        } catch (_: Throwable) {
        }
        if (owners.isEmpty()) {
            runCatching { service.getSystemService(NotificationManager::class.java).cancel(ID) }
        } else {
            refresh(service)
        }
        refreshLocationStatus()
    }

    /** True when this service currently owns a location foreground type. */
    internal fun hasLocationType(owner: String): Boolean =
        !requiresLocationType || typeStates[owner]?.hasLocation == true

    /** Keeps the user-visible "open the app once" hint in sync with the actual type. */
    fun refreshLocationStatus() {
        com.flowglass.demo.system.trigger.TriggerStatus.locationNeedsForeground =
            com.flowglass.demo.system.trigger.TriggerStatus.locationRulesActive &&
                !hasLocationType(OWNER_TRIGGER)
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
            if (com.flowglass.demo.system.trigger.TriggerStatus.locationNeedsForeground) {
                append(" · 地点触发需打开 App 一次")
            }
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

internal enum class ForegroundStartAction { NONE, SPECIAL_USE, LOCATION }

/**
 * Tracks the foreground type that was actually accepted for one service. A failed location
 * upgrade must leave the previous type untouched; otherwise a background start would silently
 * downgrade a live location service to specialUse.
 */
internal class ForegroundTypeState {
    var currentTypes: Int = 0
        private set

    val hasLocation: Boolean
        get() = currentTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0

    fun next(includeLocation: Boolean): ForegroundStartAction = when {
        hasLocation -> ForegroundStartAction.NONE
        includeLocation -> ForegroundStartAction.LOCATION
        currentTypes == 0 -> ForegroundStartAction.SPECIAL_USE
        else -> ForegroundStartAction.NONE
    }

    fun onStarted(action: ForegroundStartAction) {
        currentTypes = when (action) {
            ForegroundStartAction.NONE -> currentTypes
            ForegroundStartAction.SPECIAL_USE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            ForegroundStartAction.LOCATION -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
    }

    /** A rejected upgrade is not a downgrade: keep whatever type was already accepted. */
    fun onLocationFailure() = Unit
}
