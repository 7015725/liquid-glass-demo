package com.flowglass.demo.system.trigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.flowglass.demo.MainActivity
import com.flowglass.demo.system.exec.ExecutionRuntime
import com.flowglass.demo.ui.AppRuntime
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A dedicated `specialUse` foreground service instead of reusing KeepAliveService.
 *
 * KeepAlive's lifetime follows the user's keep-alive switches; trigger listening follows the
 * engine switch and the enabled-rule set. Keeping them separate means pausing the engine can
 * stop this service without silently turning off the user's keep-alive preference, and the
 * notification can truthfully report "正在监听 N 条自动指令".
 */
class TriggerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: TriggerHost? = null
    private var starting = false
    private val pending = mutableListOf<TriggerEvent>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundSafely(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            host?.stop(cancelAlarms = true)
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.toTriggerEvent()?.let { event ->
            val current = host
            if (current != null) current.emit(event) else pending += event
        }
        ensureHost()
        return START_STICKY
    }

    override fun onDestroy() {
        // Do not cancel alarms here: a system kill must let the alarm restart this service.
        host?.stop(cancelAlarms = false)
        host = null
        TriggerStatus.reset()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureHost() {
        if (host != null || starting) return
        starting = true
        scope.launch {
            val state = withContext(Dispatchers.IO) { AppRuntime.of(this@TriggerService).load() }
            val created = TriggerHost(
                context = this@TriggerService,
                state = state,
                scope = scope,
                onStatus = { count, _ -> updateNotification(count) },
                onShouldStop = { stopSelf() },
            )
            host = created
            created.prime(pending)
            pending.clear()
            created.start()
            starting = false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "自动指令监听", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun startForegroundSafely(count: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(count), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification(count))
            }
            TriggerStatus.serviceActive = true
        } catch (_: Throwable) {
            stopSelf()
        }
    }

    private fun updateNotification(count: Int) {
        TriggerStatus.serviceActive = true
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(count))
        } catch (_: Throwable) {
        }
    }

    private fun notification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("流光自动化正在运行")
            .setContentText("正在监听 $count 条自动指令")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_START = "com.flowglass.demo.trigger.START"
        const val ACTION_EVENT = "com.flowglass.demo.trigger.EVENT"
        const val ACTION_BOOT = "com.flowglass.demo.trigger.BOOT"
        const val ACTION_STOP = "com.flowglass.demo.trigger.STOP"
        private const val EXTRA_RULE_ID = "rule_id"
        private const val EXTRA_CMD_ID = "cmd_id"
        private const val EXTRA_TRIGGER_INDEX = "trigger_index"
        private const val EXTRA_TRIGGER_NAME = "trigger_name"
        private const val EXTRA_TIME = "time"
        private const val CHANNEL = "triggers"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context, action: String = ACTION_START, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, TriggerService::class.java).setAction(action)
            intent.configure()
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Throwable) {
            }
        }

        /** Used by screens / jobs after the engine is switched back on. */
        fun ensureStarted(context: Context) {
            ExecutionRuntime.scope.launch {
                val state = withContext(Dispatchers.IO) { AppRuntime.of(context).load() }
                if (state.running && state.autoCmds.any { it.enabled }) start(context)
            }
        }

    }
}

internal fun Intent.putAlarmEvent(
    ruleId: String,
    cmdId: Long,
    triggerIndex: Int,
    triggerName: String,
    time: String,
): Intent = apply {
    putExtra("rule_id", ruleId)
    putExtra("cmd_id", cmdId)
    putExtra("trigger_index", triggerIndex)
    putExtra("trigger_name", triggerName)
    putExtra("time", time)
}

private fun Intent.toTriggerEvent(): TriggerEvent? = when (action) {
    TriggerService.ACTION_BOOT -> TriggerEvent(
        TriggerKinds.BOOT_COMPLETED, TriggerSource.BOOT, System.currentTimeMillis(),
    )
    TriggerService.ACTION_EVENT -> {
        val ruleId = getStringExtra("rule_id") ?: return null
        val index = getIntExtra("trigger_index", -1).takeIf { it >= 0 } ?: return null
        val time = getStringExtra("time") ?: return null
        TriggerEvent(
            kind = TriggerKinds.TIME,
            source = TriggerSource.TIME,
            atEpochMs = System.currentTimeMillis(),
            data = mapOf(
                "time" to time,
                "zone_offset_minutes" to
                    (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000).toString(),
            ),
            targetRuleId = ruleId,
            targetTriggerIndex = index,
        )
    }
    else -> null
}

/** Exact or inexact alarm delivery; always restarts the foreground service with the event. */
class TriggerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return
        val index = intent.getIntExtra(EXTRA_TRIGGER_INDEX, -1)
        if (index < 0) return
        val time = intent.getStringExtra(EXTRA_TIME) ?: formatLocalNow()
        TriggerService.start(context, TriggerService.ACTION_EVENT) {
            putAlarmEvent(
                ruleId = ruleId,
                cmdId = intent.getLongExtra(EXTRA_CMD_ID, 0L),
                triggerIndex = index,
                triggerName = intent.getStringExtra(EXTRA_TRIGGER_NAME).orEmpty(),
                time = time,
            )
        }
    }

    private fun formatLocalNow(): String {
        val calendar = java.util.Calendar.getInstance()
        return "%02d:%02d".format(calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE))
    }

    companion object {
        const val ACTION_ALARM = "com.flowglass.demo.trigger.ALARM"
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_CMD_ID = "cmd_id"
        const val EXTRA_TRIGGER_INDEX = "trigger_index"
        const val EXTRA_TRIGGER_NAME = "trigger_name"
        const val EXTRA_TIME = "time"
    }
}

class TriggerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            TriggerService.start(context, TriggerService.ACTION_BOOT)
        }
    }
}
