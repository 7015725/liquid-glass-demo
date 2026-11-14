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
import com.flowglass.demo.ui.snapshot
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
    @Volatile private var host: TriggerHost? = null
    private var starting = false
    private var lastStartId = 0
    private val pending = mutableListOf<TriggerEvent>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        createChannel()
        startForegroundSafely(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            host?.stop(cancelAlarms = true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        intent?.toTriggerEvent()?.let(::deliver)
        ensureHost()
        return START_STICKY
    }

    override fun onDestroy() {
        // Do not cancel alarms here: a system kill must let the alarm restart this service.
        host?.stop(cancelAlarms = false)
        host = null
        if (current === this) current = null
        TriggerStatus.reset()
        scope.cancel()
        super.onDestroy()
    }

    /** A running host consumes the event directly, without another service start. */
    private fun deliver(event: TriggerEvent) {
        val existing = host
        if (existing == null) {
            pending += event
            return
        }
        if (!existing.isRunning) existing.start()
        if (existing.isRunning) existing.emit(event) else pending += event
    }

    private fun ensureHost() {
        val existing = host
        if (existing != null) {
            // A quick engine off -> on can leave a stopped host behind; start it again instead
            // of returning early and silently dropping every later event.
            if (!existing.isRunning) existing.start()
            return
        }
        if (starting) return
        starting = true
        scope.launch {
            try {
                val state = withContext(Dispatchers.IO) { AppRuntime.of(this@TriggerService).load() }
                val created = TriggerHost(
                    context = this@TriggerService,
                    state = state,
                    scope = scope,
                    onStatus = { count, _ -> updateNotification(count) },
                    // stopSelf(startId) only stops this start; a newer onStartCommand survives.
                    onShouldStop = { stopSelf(lastStartId) },
                )
                host = created
                created.prime(pending)
                pending.clear()
                created.start()
            } finally {
                starting = false
            }
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

        @Volatile
        private var current: TriggerService? = null

        /** Alarm fast path: true when a live in-process host accepted the event itself. */
        fun deliverIfRunning(event: TriggerEvent): Boolean {
            val service = current ?: return false
            val running = service.host ?: return false
            if (!running.isRunning) return false
            running.emit(event)
            return true
        }

        /** Returns false when the background service start was rejected by the system. */
        fun start(context: Context, action: String = ACTION_START, configure: Intent.() -> Unit = { }): Boolean {
            val intent = Intent(context, TriggerService::class.java).setAction(action)
            intent.configure()
            return try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
                true
            } catch (_: Throwable) {
                false
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
        val triggerName = intent.getStringExtra(EXTRA_TRIGGER_NAME).orEmpty()
        val cmdId = intent.getLongExtra(EXTRA_CMD_ID, 0L)
        val now = System.currentTimeMillis()
        val offset = java.util.TimeZone.getDefault().getOffset(now) / 60_000

        // If the process and host are still alive, hand the event straight to the running
        // dispatcher. Starting a foreground service from the background is restricted on
        // Android 12+ (especially without the exact-alarm exemption) and must not be the
        // default path for every alarm.
        val event = TriggerEvent(
            kind = TriggerKinds.TIME,
            source = TriggerSource.TIME,
            atEpochMs = now,
            data = mapOf("time" to time, "zone_offset_minutes" to offset.toString()),
            targetRuleId = ruleId,
            targetTriggerIndex = index,
        )
        if (TriggerService.deliverIfRunning(event)) return

        val started = TriggerService.start(context, TriggerService.ACTION_EVENT) {
            putAlarmEvent(
                ruleId = ruleId,
                cmdId = cmdId,
                triggerIndex = index,
                triggerName = triggerName,
                time = time,
            )
        }
        if (!started) recordStartFailure(context, cmdId, triggerName)
    }

    private fun recordStartFailure(context: Context, cmdId: Long, triggerName: String) {
        ExecutionRuntime.scope.launch {
            try {
                val state = withContext(Dispatchers.IO) { AppRuntime.of(context).load() }
                withContext(Dispatchers.Main.immediate) {
                    state.recordTriggerFailure(
                        cmdId,
                        "触发：${triggerName.ifBlank { "时间" }}",
                        "定时触发未能启动后台服务，请授予精确闹钟权限",
                    )
                    AppRuntime.of(context).requestSave { state.snapshot() }
                }
            } catch (_: Throwable) {
            }
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
