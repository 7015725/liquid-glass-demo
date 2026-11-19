package com.flowglass.demo.system.trigger

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.system.exec.ConditionFileAccess
import com.flowglass.demo.system.exec.ExecutionRuntime
import com.flowglass.demo.ui.AppRuntime
import com.flowglass.demo.ui.AppState
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.snapshot
import com.flowglass.demo.xposed.method.MethodHookEventSink
import com.flowglass.engine.methodhook.AppMethodCall
import com.flowglass.engine.methodhook.MethodHookVariables
import com.flowglass.engine.Clock
import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerDispatcher
import com.flowglass.engine.trigger.TriggerEnvironment
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerExecutor
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.NetworkType
import com.flowglass.engine.trigger.TriggerPlan
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSchedule
import com.flowglass.engine.trigger.TriggerSkipRecorder
import com.flowglass.engine.trigger.TriggerSource
import com.flowglass.engine.trigger.TriggerSourceRegistry
import com.flowglass.engine.trigger.TriggerTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TriggerInput(val running: Boolean, val rules: List<TriggerRule>, val permissionsEpoch: Int)

private fun AppState.triggerInput(): TriggerInput =
    TriggerInput(running, autoCmds.map(Cmd::toTriggerRule), permissionsEpoch)

internal class AndroidTriggerEnvironment(private val context: Context) : TriggerEnvironment {
    override fun zoneOffsetMinutes(atEpochMs: Long): Int =
        java.util.TimeZone.getDefault().getOffset(atEpochMs) / 60_000

    override fun batteryPercent(): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra("level", -1)
        val scale = intent.getIntExtra("scale", -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale).coerceIn(0, 100)
    }

    override fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    override fun isScreenOn(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    override fun appProcessRunning(packageName: String, userId: Int): Boolean? =
        SystemEventCache.isProcessRunning(packageName, userId)

    override fun fileExists(path: String): Boolean? = ConditionFileAccess.exists(path)

    override fun isDndOn(): Boolean? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val filter = manager.currentInterruptionFilter
        if (filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return null
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    override fun networkType(): NetworkType {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkType.NONE
        val network = connectivity.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return NetworkType.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
            else -> NetworkType.NONE
        }
    }
}

internal fun canScheduleExactAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

/**
 * Owns everything that must live as long as the foreground service: the shared event flow,
 * the reference-counted receiver set, the alarms and the dispatcher.
 */
internal class TriggerHost(
    private val context: Context,
    private val state: AppState,
    private val scope: CoroutineScope,
    private val onStatus: (listeningRules: Int, sources: Int) -> Unit,
    private val onShouldStop: () -> Unit,
) {
    private val environment = AndroidTriggerEnvironment(context)
    private val events = MutableSharedFlow<TriggerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val engine = ExecutionRuntime.engine(context)
    private val executor = object : TriggerExecutor {
        override fun isRunning(rule: TriggerRule): Boolean = state.activeExecutionCount(rule.cmdId) > 0

        override fun activeCount(rule: TriggerRule): Int = state.activeExecutionCount(rule.cmdId)

        override fun cancel(rule: TriggerRule) = state.cancelExecution(rule.cmdId)

        override fun execute(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
            readOnlyNames: Set<String>,
        ): Boolean {
            val cmd = state.find(rule.cmdId) ?: return false
            val job = state.executeWithPolicy(cmd, scope, reason, variables, rule.runMode, readOnlyNames) ?: return false
            job.invokeOnCompletion {
                dispatcher.onRunFinished(rule)
                scope.launch {
                    withContext(Dispatchers.Main.immediate) {
                        AppRuntime.of(context).requestSave { state.snapshot() }
                    }
                }
            }
            return true
        }
    }
    private val dispatcher: TriggerDispatcher = TriggerDispatcher(
        clock = Clock { System.currentTimeMillis() },
        environment = environment,
        executor = executor,
        skipRecorder = TriggerSkipRecorder { rule, reason, detail, _ ->
            state.recordSkippedRun(rule.cmdId, reason, detail)
            AppRuntime.of(context).requestSave { state.snapshot() }
        },
    )
    private val sourceRegistry = TriggerSourceRegistry(::installSource, ::uninstallSource)
    private val alarms = AlarmScheduler(context)
    private val wifiWatcher = WifiWatcher(context, scope, ::emitEvent)
    private val bluetoothWatcher = BluetoothWatcher(context, ::emitEvent)
    private val headsetWatcher = HeadsetWatcher(context, ::emitEvent)
    private val locationWatcher = LocationWatcher(context, ::emitEvent)
    private val appWatcher = AppForegroundWatcher(context, scope, ::emitEvent)
    private val rootAppWatcher = RootForegroundWatcher(context, scope, ::emitEvent)
    private val notificationListener: (String, String, String, Int) -> Unit = { app, title, text, userId ->
        // A clone and the main app share a package; the persisted app identity includes the
        // profile so a "收到通知" rule cannot fire for the wrong clone.
        val identity = if (userId == 0) app else "$app@$userId"
        emitEvent(TriggerKinds.NOTIFICATION, TriggerSource.NOTIFICATION, mapOf(
            "notification.app" to identity,
            "notification.user" to userId.toString(),
            "notification.title" to title,
            "notification.text" to text,
        ))
    }
    private var collectJob: Job? = null
    private var snapshotJob: Job? = null
    private var started = false
    private var collectorReady = false
    private val pendingEvents = ArrayDeque<TriggerEvent>()
    private var alarmSignature: String? = null
    private var lastRules: List<TriggerRule> = emptyList()

    /** Routes a verified target-process event into the normal trigger dispatcher. */
    private val methodHookSink: (String, Map<String, String>) -> Unit = { hookId, variables ->
        // The Binder callback arrives on a binder thread; keep the host's pending queue on main.
        scope.launch(Dispatchers.Main.immediate) {
            emit(
                TriggerEvent(
                    kind = TriggerKinds.METHOD_CALL,
                    source = TriggerSource.METHOD_HOOK,
                    atEpochMs = System.currentTimeMillis(),
                    data = AppMethodCall.eventData(
                        packageName = variables[MethodHookVariables.APP].orEmpty(),
                        userId = variables[MethodHookVariables.USER]?.toIntOrNull() ?: 0,
                        className = variables[MethodHookVariables.METHOD]
                            ?.substringBeforeLast('#').orEmpty(),
                        methodName = variables[MethodHookVariables.METHOD]
                            ?.substringAfterLast('#').orEmpty(),
                        signature = variables[MethodHookVariables.SIGNATURE].orEmpty(),
                    ).let { data ->
                        // The matcher compares the actual parameter types, not the display string.
                        data + ("method.parameterTypes" to variables[MethodHookVariables.SIGNATURE]
                            ?.substringAfter('(', "")
                            ?.substringBeforeLast(')', "")
                            .orEmpty())
                    },
                    methodHookVariables = variables,
                )
            )
        }
    }

    /** True while the host owns listeners and the event collector. */
    val isRunning: Boolean get() = started

    fun start() {
        if (started) return
        started = true
        MethodHookEventSink.listener = methodHookSink
        // Seed the rule set before any event is dispatched; a boot/alarm start otherwise races
        // the first snapshotFlow emission and drops the event against an empty rule list.
        val initial = state.triggerInput()
        lastRules = eligibleRules(initial.rules)
        reconcile(initial)
        if (!started) return
        while (pendingEvents.isNotEmpty()) handle(pendingEvents.removeFirst())
        collectJob = scope.launch {
            collectorReady = true
            while (pendingEvents.isNotEmpty()) handle(pendingEvents.removeFirst())
            events.collect(::handle)
        }
        scope.launch {
            snapshotFlow { state.triggerInput() }.collect { input ->
                lastRules = eligibleRules(input.rules)
                reconcile(input)
            }
        }.also { snapshotJob = it }
    }

    private fun handle(event: TriggerEvent) {
        val outcome = dispatcher.dispatch(event, lastRules)
        if (event.kind == TriggerKinds.TIME) {
            // A one-shot alarm must arm its next occurrence after this run. Only re-arm the
            // binding that fired: re-syncing every alarm at the same minute could cancel a
            // sibling alarm whose broadcast has not arrived yet.
            val target = event.targetRuleId?.let { ruleId ->
                TriggerPlan.timeBindings(lastRules).firstOrNull { binding ->
                    binding.rule.id == ruleId &&
                        (event.targetTriggerIndex == null || binding.triggerIndex == event.targetTriggerIndex)
                }
            }
            if (target != null) alarms.scheduleNext(target, canScheduleExactAlarms(context))
            else syncAlarms(lastRules, canScheduleExactAlarms(context), force = true)
        }
        if (outcome.executions.isNotEmpty() || outcome.skips.isNotEmpty()) {
            AppRuntime.of(context).requestSave { state.snapshot() }
        }
    }

    fun prime(events: List<TriggerEvent>) {
        if (!started) pendingEvents.addAll(events)
    }

    fun emit(event: TriggerEvent) {
        if (!started) {
            // Keep an event that arrives between stopSelf and the queued restart; the start
            // path drains this queue. Bound it so an engine-off period cannot grow forever.
            if (pendingEvents.size < 16) pendingEvents.addLast(event)
            return
        }
        if (!collectorReady) pendingEvents.addLast(event) else events.tryEmit(event)
    }

    fun stop(cancelAlarms: Boolean) {
        if (!started) {
            if (cancelAlarms) alarms.cancelAll()
            return
        }
        started = false
        collectorReady = false
        MethodHookEventSink.listener = null
        pendingEvents.clear()
        collectJob?.cancel()
        collectJob = null
        snapshotJob?.cancel()
        snapshotJob = null
        sourceRegistry.clear()
        if (cancelAlarms) alarms.cancelAll()
        alarmSignature = null
        dispatcher.reset()
        TriggerStatus.reset()
        lastRules = emptyList()
    }

    /** Rebuild the foreground source after the system hook's first heartbeat. */
    fun refreshAppSource() {
        if (!started) return
        // A heartbeat also proves that the system hook can receive config now. Re-send the
        // current rule-derived subscription: an app that started before the hook registered its
        // receiver may otherwise miss the process/crash families for the whole session.
        SystemHookSettings.broadcastConfig(
            context,
            SystemHookSubscriptions.requiredFamilies(lastRules),
            SystemHookSubscriptions.watchedPackages(lastRules),
        )
        if (TriggerSource.APP !in sourceRegistry.registered) return
        uninstallSource(TriggerSource.APP)
        installSource(TriggerSource.APP)
    }

    private fun reconcile(input: TriggerInput) {
        val eligible = eligibleRules(input.rules)
        if (!input.running || eligible.isEmpty()) {
            SystemHookSettings.clearSubscriptions(context)
            dispatcher.prune(emptyList())
            stop(cancelAlarms = true)
            onShouldStop()
            return
        }
        SystemHookSettings.broadcastConfig(
            context,
            SystemHookSubscriptions.requiredFamilies(eligible),
            SystemHookSubscriptions.watchedPackages(eligible),
        )
        dispatcher.prune(eligible)
        val listening = eligible.filter { rule ->
            rule.triggers.any { TriggerTypes.sourceOf(it.typeId) != null }
        }
        val required = TriggerPlan.requiredSources(eligible)
        sourceRegistry.update(required)
        val exact = canScheduleExactAlarms(context)
        TriggerStatus.exactAlarmAllowed = exact
        syncAlarms(eligible, exact)
        TriggerStatus.serviceActive = true
        TriggerStatus.listeningRules = listening.size
        TriggerStatus.registeredSources = sourceRegistry.registered.size
        TriggerStatus.listeningRuleIds = listening.map { it.cmdId }.toSet()
        TriggerStatus.locationRulesActive = eligible.any { rule ->
            rule.triggers.any {
                it.typeId == TriggerTypes.LOCATION_ENTER || it.typeId == TriggerTypes.LOCATION_EXIT
            }
        }
        onStatus(listening.size, sourceRegistry.registered.size)
    }

    private fun eligibleRules(rules: List<TriggerRule>): List<TriggerRule> =
        rules.filter { it.enabled && it.permissionsGranted(context) }

    private fun hasCloneTarget(): Boolean = lastRules.any { rule ->
        rule.triggers.any { trigger ->
            trigger.typeId == TriggerTypes.APP_FOREGROUND &&
                trigger.params["package"]?.toString()?.trim('"')?.contains('@') == true
        }
    }

    private fun syncAlarms(rules: List<TriggerRule>, exact: Boolean, force: Boolean = false) {
        val bindings = TriggerPlan.timeBindings(rules)
        val signature = bindings.joinToString("|") { binding ->
            "${binding.key}:${binding.component.params["time"]}:${binding.component.params["days"]}"
        } + "#$exact"
        if (!force && signature == alarmSignature) return
        alarms.sync(bindings, exact)
        alarmSignature = signature
    }

    private fun installSource(source: TriggerSource) {
        when (source) {
            TriggerSource.SCREEN -> register(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            })
            TriggerSource.POWER -> register(powerReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
            TriggerSource.BATTERY -> {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val sticky = register(batteryReceiver, filter)
                // Initialize the stateful baseline from the sticky broadcast.
                if (sticky != null) batteryReceiver.onReceive(context, sticky)
            }
            TriggerSource.WIFI -> wifiWatcher.start()
            TriggerSource.BLUETOOTH -> bluetoothWatcher.start()
            TriggerSource.NOTIFICATION -> NotificationEvents.add(notificationListener)
            TriggerSource.APP -> when (TriggerLayers.appForegroundLayer(context)) {
                com.flowglass.engine.ExecutionLayer.XPOSED -> Unit
                com.flowglass.engine.ExecutionLayer.ROOT -> {
                    // Normal watcher keeps main-app events; Root polls clones only.
                    appWatcher.start()
                    rootAppWatcher.start(::hasCloneTarget)
                }
                com.flowglass.engine.ExecutionLayer.NORMAL -> appWatcher.start()
            }
            TriggerSource.HEADSET -> headsetWatcher.start()
            TriggerSource.LOCATION -> locationWatcher.start()
            // TIME is an AlarmManager schedule, BOOT is a manifest receiver; SYSTEM events are
            // emitted by the already-installed LSPosed hook and need no dynamic receiver.
            TriggerSource.TIME, TriggerSource.BOOT, TriggerSource.SYSTEM, TriggerSource.METHOD_HOOK -> Unit
        }
    }

    private fun uninstallSource(source: TriggerSource) {
        when (source) {
            TriggerSource.SCREEN -> unregister(screenReceiver)
            TriggerSource.POWER -> unregister(powerReceiver)
            TriggerSource.BATTERY -> unregister(batteryReceiver)
            TriggerSource.WIFI -> wifiWatcher.stop()
            TriggerSource.BLUETOOTH -> bluetoothWatcher.stop()
            TriggerSource.NOTIFICATION -> NotificationEvents.remove(notificationListener)
            TriggerSource.APP -> {
                appWatcher.stop()
                rootAppWatcher.stop()
            }
            TriggerSource.HEADSET -> headsetWatcher.stop()
            TriggerSource.LOCATION -> locationWatcher.stop()
            TriggerSource.TIME, TriggerSource.BOOT, TriggerSource.SYSTEM, TriggerSource.METHOD_HOOK -> Unit
        }
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter): Intent? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    } catch (_: Throwable) {
        null
    }

    private fun unregister(receiver: BroadcastReceiver) {
        try { context.unregisterReceiver(receiver) } catch (_: Throwable) { }
    }

    private fun emitSimple(kind: String, source: TriggerSource, data: Map<String, String> = emptyMap()) {
        emit(TriggerEvent(kind, source, System.currentTimeMillis(), data))
    }

    private fun emitEvent(kind: String, source: TriggerSource, data: Map<String, String>) {
        emitSimple(kind, source, data)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> emitSimple(TriggerKinds.SCREEN_ON, TriggerSource.SCREEN)
                Intent.ACTION_SCREEN_OFF -> emitSimple(TriggerKinds.SCREEN_OFF, TriggerSource.SCREEN)
                Intent.ACTION_USER_PRESENT -> emitSimple(TriggerKinds.USER_PRESENT, TriggerSource.SCREEN)
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED ->
                    emitSimple(TriggerKinds.POWER_CONNECTED, TriggerSource.POWER)
                Intent.ACTION_POWER_DISCONNECTED ->
                    emitSimple(TriggerKinds.POWER_DISCONNECTED, TriggerSource.POWER)
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            if (level < 0 || scale <= 0) return
            val percent = (level * 100 / scale).coerceIn(0, 100)
            emitSimple(
                TriggerKinds.BATTERY, TriggerSource.BATTERY,
                mapOf("battery" to percent.toString()),
            )
        }
    }
}

/** Per-trigger exact/inexact alarms; the persisted key set lets a restart cancel stale alarms. */
internal class AlarmScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences("trigger_alarms", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(AlarmManager::class.java)

    fun sync(bindings: List<TriggerBinding>, exact: Boolean) {
        val desired = bindings.associateBy { it.key }
        val previous = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        (previous - desired.keys).toList().forEach { key ->
            cancel(key)
            previous.remove(key)
        }
        for ((key, binding) in desired) {
            if (scheduleNext(binding, exact)) previous += key
        }
        prefs.edit().putStringSet(KEY, previous).apply()
    }

    /** Arms the next occurrence of one binding; returns false when its parameters are unusable. */
    fun scheduleNext(binding: TriggerBinding, exact: Boolean): Boolean {
        val time = binding.component.params["time"]?.toString()?.trim('"') ?: return false
        val days = binding.component.params["days"]?.toString()?.trim('"')
        val next = TriggerSchedule.nextOccurrence(
            System.currentTimeMillis(),
            java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000,
            time,
            days,
        ) ?: return false
        Log.i(TriggerService.ALARM_TAG, "arming next alarm key=${binding.key} at=$next exact=$exact")
        schedule(binding, next, exact)
        return true
    }

    fun cancelAll() {
        prefs.getStringSet(KEY, emptySet()).orEmpty().toList().forEach { cancel(it) }
        prefs.edit().remove(KEY).apply()
    }

    private fun schedule(binding: TriggerBinding, atEpochMs: Long, exact: Boolean) {
        val alarm = manager ?: return
        val pending = pendingIntent(binding, PendingIntent.FLAG_UPDATE_CURRENT)
        alarm.cancel(pending)
        try {
            if (exact) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pending)
            }
        } catch (_: SecurityException) {
            // Some ROMs revoke exact alarms between the check and the call; retry inexact.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pending)
        }
    }

    private fun cancel(key: String) {
        val binding = key.split('#')
        if (binding.size != 2) return
        val index = binding[1].toIntOrNull() ?: return
        val intent = Intent(context, TriggerAlarmReceiver::class.java).apply {
            action = TriggerAlarmReceiver.ACTION_ALARM
            putExtra(TriggerAlarmReceiver.EXTRA_RULE_ID, binding[0])
            putExtra(TriggerAlarmReceiver.EXTRA_TRIGGER_INDEX, index)
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCode(key), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        manager?.cancel(pending)
        pending.cancel()
    }

    private fun pendingIntent(binding: TriggerBinding, flags: Int): PendingIntent {
        val intent = Intent(context, TriggerAlarmReceiver::class.java).apply {
            action = TriggerAlarmReceiver.ACTION_ALARM
            putExtra(TriggerAlarmReceiver.EXTRA_RULE_ID, binding.rule.id)
            putExtra(TriggerAlarmReceiver.EXTRA_CMD_ID, binding.rule.cmdId)
            putExtra(TriggerAlarmReceiver.EXTRA_TRIGGER_INDEX, binding.triggerIndex)
            putExtra(TriggerAlarmReceiver.EXTRA_TRIGGER_NAME, binding.component.name)
            putExtra(
                TriggerAlarmReceiver.EXTRA_TIME,
                binding.component.params["time"]?.toString()?.trim('"').orEmpty(),
            )
        }
        return PendingIntent.getBroadcast(
            context, requestCode(binding.key), intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(key: String): Int = key.hashCode() and 0x3FFFFFFF

    companion object {
        private const val KEY = "scheduled"
    }
}
