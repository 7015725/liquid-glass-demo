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
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.system.exec.ConditionFileAccess
import com.flowglass.demo.system.exec.ExecutionRuntime
import com.flowglass.demo.system.log.LogSettings
import com.flowglass.demo.system.log.recordDiagnostic
import com.flowglass.demo.ui.AppRuntime
import com.flowglass.demo.ui.AppState
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.snapshot
import com.flowglass.demo.xposed.method.MethodHookEventSink
import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceLevel
import com.flowglass.engine.log.TraceStatus
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

internal class AndroidTriggerEnvironment(
    private val context: Context,
    private val persisted: (String) -> Map<String, Any?> = { emptyMap() },
) : TriggerEnvironment {
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

    override fun persistedVariables(ruleId: String): Map<String, Any?> = persisted(ruleId)

    override fun isDndOn(): Boolean? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val filter = manager.currentInterruptionFilter
        if (filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return null
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    @Suppress("DEPRECATION")
    override fun wifiSsid(): String? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val raw = runCatching { wifi.connectionInfo?.ssid }.getOrNull() ?: return null
        val ssid = raw.trim().removeSurrounding("\"")
        return ssid.takeIf { it.isNotEmpty() && !it.equals("<unknown ssid>", ignoreCase = true) }
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
    private val environment = AndroidTriggerEnvironment(context) { ruleId -> state.persistedVariablesFor(ruleId) }
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
        ): Boolean = executeInternal(rule, binding, reason, variables, readOnlyNames, "")

        override fun executeTraced(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
            readOnlyNames: Set<String>,
            traceId: String,
        ): Boolean = executeInternal(rule, binding, reason, variables, readOnlyNames, traceId)

        private fun executeInternal(
            rule: TriggerRule,
            binding: TriggerBinding,
            reason: String,
            variables: Map<String, Any?>,
            readOnlyNames: Set<String>,
            traceId: String,
        ): Boolean {
            val cmd = state.find(rule.cmdId) ?: return false
            val job = state.executeWithPolicy(
                cmd,
                scope,
                reason,
                variables,
                rule.runMode,
                readOnlyNames,
                traceId.ifBlank { null },
            ) ?: return false
            job.invokeOnCompletion {
                // The engine completes on its own dispatcher; the completion mutates the dispatcher
                // queue and the execution maps, so it belongs to the same serial worker as events.
                TriggerWorker.scope.launch {
                    dispatcher.onRunFinished(rule)
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
            TriggerWorker.scope.launch {
                state.recordSkippedRun(rule.cmdId, reason, detail)
                AppRuntime.of(context).requestSave { state.snapshot() }
            }
        },
        trace = AppRuntime.of(context).logStore,
        includeRawEvents = { LogSettings(context).includeRawEvents },
    )
    private val sourceRegistry = TriggerSourceRegistry(::installSource, ::uninstallSource)
    private fun traceSource(
        source: TriggerSource,
        status: TraceStatus,
        message: String,
        error: Throwable? = null,
    ) {
        val level = when {
            error != null || status == TraceStatus.FAILURE -> TraceLevel.ERROR
            status == TraceStatus.SKIPPED -> TraceLevel.WARN
            else -> TraceLevel.INFO
        }
        AppRuntime.of(context).logStore.recordDiagnostic(
            kind = TraceKind.SOURCE,
            status = status,
            source = source.name,
            message = message,
            level = level,
            error = error,
        )
    }
    private val alarms = AlarmScheduler(context)
    private val wifiWatcher = WifiWatcher(context, scope, ::emitEvent)
    private val bluetoothWatcher = BluetoothWatcher(context, ::emitEvent)
    private val headsetWatcher = HeadsetWatcher(context, ::emitEvent)
    private val locationWatcher = LocationWatcher(context, ::emitEvent)
    private val appWatcher = AppForegroundWatcher(context, scope, ::emitEvent)
    private val rootAppWatcher = RootForegroundWatcher(context, scope, ::emitEvent)
    private val systemBroadcastSource = SystemBroadcastSource(context, scope, ::emit) { lastRules }
    private val httpTriggerServer = HttpTriggerServer(context, scope, ::emit, { lastRules }) { message, error ->
        traceSource(TriggerSource.HTTP, TraceStatus.FAILURE, message, error)
    }
    private val phoneStateSource = PhoneStateSource(context, ::emit)
    private val calendarWatcher = CalendarWatcher(context, ::emit)
    private val mediaTrackSource = MediaTrackSource(context, ::emit)
    private val notificationListener: (NotificationInfo) -> Unit = { info ->
        // A clone and the main app share a package; the persisted app identity includes the
        // profile so a notification rule cannot fire for the wrong clone.
        val identity = if (info.userId == 0) info.app else "${info.app}@${info.userId}"
        emitEvent(
            if (info.removed) TriggerKinds.NOTIFICATION_REMOVED else TriggerKinds.NOTIFICATION,
            TriggerSource.NOTIFICATION,
            mapOf(
                "notification.app" to identity,
                "notification.user" to info.userId.toString(),
                "notification.title" to info.title,
                "notification.text" to info.text,
                "notification.subtext" to info.subtext,
                "notification.category" to info.category,
                "notification.channel" to info.channel,
                "notification.ongoing" to info.ongoing.toString(),
            ),
        )
    }
    private var collectJob: Job? = null
    private var snapshotJob: Job? = null
    @Volatile private var started = false
    @Volatile private var collectorReady = false
    /** Bumped when a session starts or a teardown is queued, so a stale teardown cannot apply. */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var pendingCancelAlarms = false
    private val pendingEvents = ArrayDeque<TriggerEvent>()
    private var alarmSignature: String? = null
    @Volatile private var lastRules: List<TriggerRule> = emptyList()
    private var lastBlockedSources: Set<TriggerSource> = emptySet()
    private val eventSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** Routes a verified target-process event into the normal trigger dispatcher. */
    private val methodHookSink: (String, Map<String, String>) -> Unit = { hookId, variables ->
        // The Binder callback arrives on a binder thread; emit() hands it to the serial worker.
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
                        .orEmpty()) + ("hook.id" to hookId)
                },
                methodHookVariables = variables,
            ),
        )
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
        val session = generation.incrementAndGet()
        pendingCancelAlarms = false
        collectJob = TriggerWorker.scope.launch {
            if (!started || generation.get() != session) return@launch
            collectorReady = true
            // Reconcile on the worker that will evaluate events: a blocking file condition inside a
            // rule must never stall the UI, and the dispatcher queue has exactly one owner.
            reconcile(initial)
            if (!started) return@launch
            while (pendingEvents.isNotEmpty()) handle(pendingEvents.removeFirst())
            var lastSeq = 0L
            events.collect { event ->
                if (event.seq > 0L) {
                    if (lastSeq > 0L && event.seq > lastSeq + 1) {
                        val dropped = (event.seq - lastSeq - 1).toInt()
                        TriggerStatus.droppedEvents += dropped
                        TriggerStatus.recordDrop(event.source, dropped)
                    }
                    lastSeq = event.seq
                }
                handle(event)
            }
        }
        TriggerWorker.scope.launch {
            snapshotFlow { state.triggerInput() }.collect { input ->
                if (generation.get() != session) return@collect
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
        // Callers arrive on binder, sensor and broadcast threads; the pending queue and the shared
        // flow belong to the worker, so hand the event over instead of touching them here.
        TriggerWorker.scope.launch { deliver(event) }
    }

    private fun deliver(event: TriggerEvent) {
        if (!started) {
            // Keep an event that arrives between stopSelf and the queued restart; the start
            // path drains this queue. Bound it so an engine-off period cannot grow forever.
            if (pendingEvents.size < 16) pendingEvents.addLast(event)
            else {
                TriggerStatus.droppedEvents += 1
                TriggerStatus.recordDrop(event.source)
            }
            return
        }
        // Both windows share one bound: a start-up burst must not grow the queue without limit.
        if (!collectorReady) {
            if (pendingEvents.size < 16) pendingEvents.addLast(event)
            else {
                TriggerStatus.droppedEvents += 1
                TriggerStatus.recordDrop(event.source)
            }
        } else {
            events.tryEmit(event.copy(seq = eventSeq.incrementAndGet()))
        }
    }

    fun stop(cancelAlarms: Boolean) {
        val wasStarted = started
        // Only the flags are touched here: everything below belongs to the serial worker, which may
        // still be inside a blocking condition. Clearing its maps from this thread would corrupt
        // them, so the teardown is queued behind that work instead.
        started = false
        collectorReady = false
        MethodHookEventSink.listener = null
        // Cancelling a job is safe from any thread; a run already inside a blocking condition keeps
        // the worker busy, so the collection teardown below still has to wait its turn.
        collectJob?.cancel()
        collectJob = null
        snapshotJob?.cancel()
        snapshotJob = null
        if (cancelAlarms) pendingCancelAlarms = true
        // A later stop (onDestroy right after ACTION_STOP) must not invalidate a teardown that is
        // still waiting behind a blocking run: only the session that was running owns the token.
        if (!wasStarted && !cancelAlarms) return
        val teardown = if (wasStarted) generation.incrementAndGet() else generation.get()
        TriggerWorker.scope.launch {
            if (generation.get() != teardown || started) return@launch
            pendingEvents.clear()
            sourceRegistry.clear()
            if (pendingCancelAlarms) {
                pendingCancelAlarms = false
                alarms.cancelAll()
            }
            alarmSignature = null
            dispatcher.reset()
            TriggerStatus.reset()
            lastRules = emptyList()
        }
    }

    /** Rebuild the foreground source after the system hook's first heartbeat. */
    fun refreshAppSource() {
        if (!started) return
        // The source registry and the watchers belong to the worker: rebuilding them from the main
        // thread would race the reconcile that is installing or clearing the same sources.
        TriggerWorker.scope.launch {
            if (!started) return@launch
            SystemHookSettings.broadcastConfig(
                context,
                SystemHookSubscriptions.requiredFamilies(lastRules),
                SystemHookSubscriptions.watchedPackages(lastRules),
            )
            if (TriggerSource.APP !in sourceRegistry.registered) return@launch
            uninstallSource(TriggerSource.APP)
            installSource(TriggerSource.APP)
        }
    }

    private fun reconcile(input: TriggerInput) {
        val eligible = eligibleRules(input.rules)
        val blockedSources = input.rules.asSequence()
            .filter { it.enabled && !it.permissionsGranted(context) }
            .flatMap { it.triggers.asSequence() }
            .mapNotNull { TriggerTypes.sourceOf(it.typeId.orEmpty()) }
            .toSet()
        (blockedSources - lastBlockedSources).forEach { source ->
            traceSource(source, TraceStatus.SKIPPED, "触发器源缺少权限，相关规则未监听")
        }
        lastBlockedSources = blockedSources
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
        if (TriggerSource.BROADCAST in sourceRegistry.registered) {
            systemBroadcastSource.sync(eligible)
        }
        if (TriggerSource.HTTP in sourceRegistry.registered) {
            httpTriggerServer.sync()
        }
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
        try {
        when (source) {
            TriggerSource.SCREEN -> register(TriggerSource.SCREEN, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            })
            TriggerSource.POWER -> register(TriggerSource.POWER, powerReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
            TriggerSource.BATTERY -> {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val sticky = register(TriggerSource.BATTERY, batteryReceiver, filter)
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
            TriggerSource.BROADCAST -> systemBroadcastSource.start()
            TriggerSource.HTTP -> httpTriggerServer.sync()
            TriggerSource.PHONE -> phoneStateSource.start()
            TriggerSource.CALENDAR -> calendarWatcher.start()
            TriggerSource.MEDIA -> mediaTrackSource.start()
            // TIME is an AlarmManager schedule, BOOT and SMS are manifest receivers; SYSTEM events
            // are emitted by the already-installed LSPosed hook. INTENT/TILE/SHARE arrive through
            // manifest components and need no dynamic listener.
            TriggerSource.TIME, TriggerSource.BOOT, TriggerSource.SYSTEM, TriggerSource.METHOD_HOOK,
            TriggerSource.INTENT, TriggerSource.TILE, TriggerSource.SHARE, TriggerSource.SMS -> Unit
        }
            traceSource(source, TraceStatus.STARTED, "触发器源已启动")
        } catch (t: Throwable) {
            traceSource(source, TraceStatus.FAILURE, "触发器源启动失败", t)
            Log.e("TriggerHost", "install source $source failed", t)
        }
    }

    private fun uninstallSource(source: TriggerSource) {
        try {
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
            TriggerSource.BROADCAST -> systemBroadcastSource.stop()
            TriggerSource.HTTP -> httpTriggerServer.stop()
            TriggerSource.PHONE -> phoneStateSource.stop()
            TriggerSource.CALENDAR -> calendarWatcher.stop()
            TriggerSource.MEDIA -> mediaTrackSource.stop()
            TriggerSource.TIME, TriggerSource.BOOT, TriggerSource.SYSTEM, TriggerSource.METHOD_HOOK,
            TriggerSource.INTENT, TriggerSource.TILE, TriggerSource.SHARE, TriggerSource.SMS -> Unit
        }
            traceSource(source, TraceStatus.INFO, "触发器源已停止")
        } catch (t: Throwable) {
            traceSource(source, TraceStatus.FAILURE, "触发器源停止失败", t)
            Log.e("TriggerHost", "uninstall source $source failed", t)
        }
    }

    private fun register(source: TriggerSource, receiver: BroadcastReceiver, filter: IntentFilter): Intent? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    } catch (t: Throwable) {
        traceSource(source, TraceStatus.FAILURE, "广播监听注册失败", t)
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
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val full = status == BatteryManager.BATTERY_STATUS_FULL
            emitSimple(
                TriggerKinds.BATTERY_FULL, TriggerSource.BATTERY,
                mapOf("battery" to percent.toString(), "full" to full.toString()),
            )
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temperature > 0) {
                emitSimple(
                    TriggerKinds.BATTERY_OVERHEAT, TriggerSource.BATTERY,
                    mapOf(
                        "battery" to percent.toString(),
                        "battery.temperature" to (temperature / 10).toString(),
                    ),
                )
            }
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
