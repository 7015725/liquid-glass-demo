package com.flowglass.demo.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * System-server side of the LSPosed integration. It is deliberately read-only: hooks only observe
 * candidate methods and enqueue package/user data; nothing is replaced or mutated. The actual
 * broadcast is sent by [SystemHookDispatcher] on its own HandlerThread, after the callback has
 * returned and outside the WindowManager/ActivityManager locks held by the hooked methods.
 *
 * Every callback and every posted Runnable is routed through [HookFailureGuard]: an unexpected
 * ROM layout or SecurityException is logged and can never escape into system_server.
 */
internal object SystemHook {
    private val enabled = AtomicBoolean(false)
    private val subscribedFamilies = AtomicInteger(SystemHookFamily.defaultMask)
    private val watchedKeys = AtomicReference<Set<String>>(emptySet())
    private val lastIdentity = AtomicReference<String?>(null)
    private val lastKeyguard = AtomicReference<Boolean?>(null)
    private val lastProcessEvent = ConcurrentHashMap<String, Long>()
    private val lastMediaState = ConcurrentHashMap<String, String>()
    private val lastForegroundServiceState = ConcurrentHashMap<String, String>()
    private val stateGate = ContentGate()
    private val stateLogLimiter = BroadcastRateLimiter(STATE_LOG_MIN_INTERVAL_MS)
    private val loadedLimiter = BroadcastRateLimiter(LOADED_MIN_INTERVAL_MS)
    private val installedCounts = ConcurrentHashMap<SystemHookFamily, Int>()
    private val sequence = AtomicLong(0L)
    @Volatile private var bridge: HookBridge? = null
    @Volatile private var systemContext: Context? = null
    @Volatile private var receiverRegistered = false

    /** android.media.PlaybackState constants, copied to avoid a compile-time system dependency. */
    private const val MEDIA_STATE_STOPPED = 1
    private const val MEDIA_STATE_PAUSED = 2
    private const val MEDIA_STATE_PLAYING = 3
    @Volatile private var heartbeat: Runnable? = null
    private val heartbeatLock = Any()
    private val skipped = ConcurrentHashMap<String, Boolean>()
    private val dispatcher: SystemHookDispatcher by lazy {
        SystemHookDispatcher(
            capacity = EVENT_QUEUE_CAPACITY,
            log = ::log,
            sender = ::sendEvent,
        )
    }

    fun install(hookBridge: HookBridge, classLoader: ClassLoader) {
        safe("install") {
            bridge = hookBridge
            dispatcher.warmUp()
            // Install detection points before any receiver can request a heartbeat, so the first
            // heartbeat always reports the real installed count (including zero).
            hookForegroundCandidates(classLoader)
            hookProcessCandidates(classLoader)
            hookKeyguardCandidates(classLoader)
            hookMediaCandidates(classLoader)
            hookForegroundServiceCandidates(classLoader)
            hookSystemContext(classLoader)
        }
    }

    private fun bridge(): HookBridge = bridge ?: error("HookBridge not installed")

    private fun hookSystemContext(classLoader: ClassLoader) {
        val activityThread = bridge().findClass("android.app.ActivityThread", classLoader)
        bridge().hookAllMethods(activityThread, "systemMain", HookCallback { _, _, _ ->
            safe("systemMain callback") {
                postToMain("register state receiver (systemMain)", classLoader)
            }
        })
        // Try immediately as well: some LSPosed versions handleLoadPackage is already running
        // after the system context exists, so systemMain may have been called before our hook.
        postToMain("register state receiver (initial)", classLoader)
        // Some ROMs install modules too late for systemMain; startBootstrapServices runs after
        // the system context exists and gives us a second, harmless registration attempt.
        runCatching {
            val systemServer = bridge().findClass("com.android.server.SystemServer", classLoader)
            bridge().hookAllMethods(systemServer, "startBootstrapServices", HookCallback { _, _, _ ->
                safe("bootstrap callback") {
                    postToMain("register state receiver (bootstrap)", classLoader)
                }
            })
        }.onFailure { log("bootstrap hook unavailable: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            val ams = bridge().findClass("com.android.server.am.ActivityManagerService", classLoader)
            bridge().hookAllMethods(ams, "systemReady", HookCallback { _, _, _ ->
                safe("AMS systemReady callback") {
                    postToMain("register state receiver (ams)", classLoader)
                }
            })
        }.onFailure { log("AMS systemReady hook unavailable: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Schedules on the main Looper; both post() and the posted Runnable are protected. */
    private fun postToMain(where: String, classLoader: ClassLoader) {
        HookFailureGuard.post(
            handler = Handler(Looper.getMainLooper()),
            where = where,
            log = ::log,
        ) {
            registerStateReceiver(classLoader)
        }
    }

    private fun registerStateReceiver(classLoader: ClassLoader) {
        if (receiverRegistered) return
        val context = systemContext ?: runCatching {
            val threadClass = bridge().findClass("android.app.ActivityThread", classLoader)
            val thread = bridge().callStaticMethod(threadClass, "currentActivityThread")
            bridge().callMethod(thread, "getSystemContext") as? Context
        }.getOrNull() ?: return
        systemContext = context
        val filter = IntentFilter().apply {
            addAction(SystemHookProtocol.ACTION_STATE)
            addAction(SystemHookProtocol.ACTION_CONFIG)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                stateReceiver,
                filter,
                SystemHookProtocol.PERMISSION,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(stateReceiver, filter, SystemHookProtocol.PERMISSION, null)
        }
        receiverRegistered = true
        log("state receiver registered")
        // Announce availability regardless of the switch. If the app started first, its earlier
        // state broadcast was lost; this announcement makes it reply with the persisted switch.
        sendLoaded(context)
        if (enabled.get()) startHeartbeat(context)
    }

    private fun hookForegroundCandidates(classLoader: ClassLoader) {
        hookCandidates(
            classLoader = classLoader,
            candidates = SystemHookCandidates.resumed,
            hookFactory = ::foregroundHook,
            family = SystemHookFamily.FOREGROUND,
        )
    }

    private fun hookProcessCandidates(classLoader: ClassLoader) {
        hookCandidates(
            classLoader = classLoader,
            candidates = SystemHookCandidates.process,
            hookFactory = ::processHook,
            family = null,
        )
    }

    private fun hookKeyguardCandidates(classLoader: ClassLoader) {
        hookCandidates(
            classLoader = classLoader,
            candidates = SystemHookCandidates.keyguard,
            hookFactory = ::keyguardHook,
            family = SystemHookFamily.KEYGUARD,
        )
    }

    private fun hookMediaCandidates(classLoader: ClassLoader) {
        hookCandidates(
            classLoader = classLoader,
            candidates = SystemHookCandidates.media,
            hookFactory = ::mediaHook,
            family = SystemHookFamily.MEDIA,
        )
    }

    private fun hookForegroundServiceCandidates(classLoader: ClassLoader) {
        hookCandidates(
            classLoader = classLoader,
            candidates = SystemHookCandidates.foregroundService,
            hookFactory = ::foregroundServiceHook,
            family = SystemHookFamily.FOREGROUND_SERVICE,
        )
    }

    /**
     * Hooks every candidate once and records its own count. A family can contain candidates from
     * several classes; the total is what makes that family available to the app.
     */
    private fun hookCandidates(
        classLoader: ClassLoader,
        candidates: List<HookCandidate>,
        hookFactory: (HookCandidate) -> HookCallback,
        family: SystemHookFamily?,
    ) {
        for (candidate in candidates) {
            var classFound = false
            try {
                val clazz = bridge().findClass(candidate.className, classLoader)
                classFound = true
                val count = bridge().hookAllMethods(clazz, candidate.methodName, hookFactory(candidate))
                if (count > 0) {
                    val targetFamily = family ?: candidate.family
                    installedCounts.merge(targetFamily, count, Int::plus)
                }
                log(
                    "candidate ${candidate.label}: class=found family=${(family ?: candidate.family).name} " +
                        "hooks=$count"
                )
            } catch (failure: Throwable) {
                log(
                    "candidate ${candidate.label}: class=${if (classFound) "found" else "missing"} " +
                        "family=${(family ?: candidate.family).name} hooks=0 " +
                        "(${failure.javaClass.simpleName}: ${failure.message})"
                )
            }
        }
        val active = installedCounts.entries
            .filter { it.value > 0 }
            .joinToString(",") { "${it.key.name}(${it.value})" }
        log("hooks installed=${installedHooks()} active=${active.ifEmpty { "none" }}")
    }

    private fun foregroundHook(candidate: HookCandidate): HookCallback =
        HookCallback { thisObject, args, result ->
            safe("foreground event ${candidate.label}") {
                observeForeground(candidate, thisObject, args, result)
            }
        }

    private fun processHook(candidate: HookCandidate): HookCallback =
        HookCallback { thisObject, args, result ->
            safe("process event ${candidate.label}") {
                observeProcess(candidate, thisObject, args, result)
            }
        }

    private fun keyguardHook(candidate: HookCandidate): HookCallback =
        HookCallback { thisObject, args, result ->
            safe("keyguard event ${candidate.label}") {
                observeKeyguard(candidate, thisObject, args, result)
            }
        }

    private fun mediaHook(candidate: HookCandidate): HookCallback =
        HookCallback { thisObject, args, result ->
            safe("media event ${candidate.label}") {
                observeMedia(candidate, thisObject, args, result)
            }
        }

    private fun foregroundServiceHook(candidate: HookCandidate): HookCallback =
        HookCallback { thisObject, args, result ->
            safe("foreground-service event ${candidate.label}") {
                observeForegroundService(candidate, thisObject, args, result)
            }
        }

    /**
     * Runs on the hooked system-server thread while WMS/AMS locks may be held. It must not send a
     * broadcast or call into ActivityManager here; only copy plain data into the bounded queue.
     */
    private fun observeForeground(
        candidate: HookCandidate,
        thisObject: Any?,
        arguments: Array<Any?>,
        result: Any?,
    ) {
        if (!canEmit(SystemHookFamily.FOREGROUND)) return
        val record = when (candidate.source) {
            HookEventSource.FIRST_ARGUMENT_ACTIVITY -> arguments.getOrNull(0)
            HookEventSource.THIS_ACTIVITY_STATE -> {
                if (!isResumedState(arguments.getOrNull(0))) return
                thisObject
            }
            else -> return
        } ?: run {
            logSkip(candidate, "activity record is null")
            return
        }
        val identity = readActivityIdentity(record) ?: run {
            logSkip(candidate, "packageName/mUserId unavailable")
            return
        }
        if (lastIdentity.get() == identity.key) return
        lastIdentity.set(identity.key)
        emit(SystemHookFamily.FOREGROUND, SystemHookProtocol.EVENT_FOREGROUND, identity)
    }

    private fun observeProcess(
        candidate: HookCandidate,
        thisObject: Any?,
        arguments: Array<Any?>,
        result: Any?,
    ) {
        if (!canEmit(candidate.family)) return
        val identity = when (candidate.source) {
            HookEventSource.SECOND_ARGUMENT_APPLICATION -> {
                // startProcessLocked returns null when the process was not actually started.
                if (result == null) {
                    logSkip(candidate, "startProcessLocked returned null")
                    return
                }
                readApplicationIdentity(arguments.getOrNull(1))
            }
            HookEventSource.FIRST_ARGUMENT_PROCESS -> readProcessIdentity(arguments.getOrNull(0))
            HookEventSource.SECOND_ARGUMENT_PROCESS -> readProcessIdentity(arguments.getOrNull(1))
            HookEventSource.PROCESS_START_ARGUMENTS ->
                readProcessStartIdentity(arguments, result)
            HookEventSource.CLEANUP_PROCESS_RECORD -> {
                // A restarting app is not an exit; it immediately gets a new process.
                if (arguments.getOrNull(2) == true) return
                readProcessIdentity(arguments.getOrNull(0))
            }
            HookEventSource.PROCESS_DIED_RECORD -> {
                // handleAppDiedLocked(ProcessRecord, restarting, allowRestart): a crash/restart
                // is not an exit. The cleanup callback may arrive separately for a real exit.
                if (arguments.getOrNull(1) == true) return
                readProcessIdentity(arguments.getOrNull(0))
            }
            else -> null
        } ?: run {
            logSkip(candidate, "ProcessRecord identity unavailable")
            return
        }
        if (!isWatched(identity)) {
            logSkip(candidate, "package/user not subscribed")
            return
        }
        if (!dedupeProcess(identity, candidate.kind)) return
        emit(candidate.family, candidate.kind, identity)
    }

    private fun observeKeyguard(
        candidate: HookCandidate,
        thisObject: Any?,
        arguments: Array<Any?>,
        result: Any?,
    ) {
        if (!canEmit(SystemHookFamily.KEYGUARD)) return
        // 12 是 (boolean, boolean)，13–15 是 (int displayId, boolean, boolean)：按类型取第一个
        // Boolean，不能固定取 args[0]。
        val showing = SystemHookArguments.keyguardShowing(arguments) ?: run {
            logSkip(candidate, "keyguard boolean unavailable")
            return
        }
        if (lastKeyguard.getAndSet(showing) == showing) return
        val kind = if (showing) {
            SystemHookProtocol.EVENT_KEYGUARD_LOCKED
        } else {
            SystemHookProtocol.EVENT_KEYGUARD_UNLOCKED
        }
        emit(SystemHookFamily.KEYGUARD, kind, null)
    }

    private fun observeMedia(
        candidate: HookCandidate,
        thisObject: Any?,
        arguments: Array<Any?>,
        result: Any?,
    ) {
        if (!canEmit(SystemHookFamily.MEDIA)) return
        val record = arguments.getOrNull(0) ?: run {
            logSkip(candidate, "media session record unavailable")
            return
        }
        val identity = readMediaIdentity(record) ?: run {
            logSkip(candidate, "media package/user unavailable")
            return
        }
        if (!isWatched(identity)) {
            logSkip(candidate, "media package/user not subscribed")
            return
        }
        val kind = when (candidate.source) {
            HookEventSource.FIRST_ARGUMENT_MEDIA_SESSION -> SystemHookProtocol.EVENT_MEDIA_STOPPED
            HookEventSource.FIRST_ARGUMENT_MEDIA_SESSION_WITH_PLAYBACK_STATE -> {
                // 12–14 的回调没有 PlaybackState 参数（只有 record 和 shouldUpdatePriority），
                // 15 才可能带第三个参数；按类型找，找不到就放弃。
                val playback = SystemHookArguments.playbackState(arguments) ?: return
                val state = runCatching {
                    bridge().callMethod(playback, "getState") as? Number
                }.getOrNull()?.toInt() ?: return
                when (state) {
                    MEDIA_STATE_PLAYING -> SystemHookProtocol.EVENT_MEDIA_PLAYING
                    MEDIA_STATE_PAUSED -> SystemHookProtocol.EVENT_MEDIA_PAUSED
                    MEDIA_STATE_STOPPED -> SystemHookProtocol.EVENT_MEDIA_STOPPED
                    else -> return
                }
            }
            else -> return
        }
        if (lastMediaState.put(identity.key, kind) == kind) return
        emit(SystemHookFamily.MEDIA, kind, identity)
    }

    private fun observeForegroundService(
        candidate: HookCandidate,
        thisObject: Any?,
        arguments: Array<Any?>,
        result: Any?,
    ) {
        if (!canEmit(SystemHookFamily.FOREGROUND_SERVICE)) return
        val record = arguments.getOrNull(0) ?: run {
            logSkip(candidate, "service record unavailable")
            return
        }
        val notificationId = (arguments.getOrNull(1) as? Number)?.toInt() ?: run {
            logSkip(candidate, "foreground notification id unavailable")
            return
        }
        val identity = readServiceIdentity(record) ?: run {
            logSkip(candidate, "service package/user unavailable")
            return
        }
        if (!isWatched(identity)) {
            logSkip(candidate, "service package/user not subscribed")
            return
        }
        val kind = if (notificationId != 0) {
            SystemHookProtocol.EVENT_FOREGROUND_SERVICE_STARTED
        } else {
            SystemHookProtocol.EVENT_FOREGROUND_SERVICE_STOPPED
        }
        if (lastForegroundServiceState.put(identity.key, kind) == kind) return
        emit(SystemHookFamily.FOREGROUND_SERVICE, kind, identity)
    }

    private fun emit(family: SystemHookFamily, kind: String, identity: HookIdentity?) {
        if (!canEmit(family)) return
        dispatcher.enqueue(
            HookEvent(
                family = family,
                kind = kind,
                packageName = identity?.packageName,
                userId = identity?.userId,
                atEpochMs = System.currentTimeMillis(),
            )
        )
    }

    private fun canEmit(family: SystemHookFamily): Boolean =
        enabled.get() && (subscribedFamilies.get() and family.mask) != 0

    private fun isWatched(identity: HookIdentity): Boolean =
        watchedKeys.get().contains(identity.key)

    private fun dedupeProcess(identity: HookIdentity, kind: String): Boolean {
        val key = "${identity.key}:$kind"
        val now = System.currentTimeMillis()
        val previous = lastProcessEvent.put(key, now) ?: return true
        if (now - previous < PROCESS_DEDUPE_MS) return false
        return true
    }

    private fun isResumedState(state: Any?): Boolean {
        if (state == null) return false
        val name = runCatching { bridge().callMethod(state, "name") as? String }.getOrNull()
        return name == "RESUMED"
    }

    private fun readActivityIdentity(record: Any?): HookIdentity? {
        if (record == null) return null
        val packageName = runCatching {
            bridge().getObjectField(record, "packageName") as? String
        }.getOrNull()
        val userId = when (val raw = runCatching {
            bridge().getObjectField(record, "mUserId")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> null
        }
        return HookIdentityReader.of(packageName, userId)
    }

    private fun readApplicationIdentity(info: Any?): HookIdentity? {
        if (info == null) return null
        val packageName = runCatching {
            bridge().getObjectField(info, "packageName") as? String
        }.getOrNull()
        val uid = when (val raw = runCatching {
            bridge().getObjectField(info, "uid")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> null
        } ?: return null
        return HookIdentityReader.of(packageName, uid / 100_000)
    }

    /**
     * ProcessList#startProcessLocked is overloaded on Android 10/11:
     *  - String processName + ApplicationInfo (the real start path, returns ProcessRecord)
     *  - ProcessRecord + HostingRecord (returns boolean/void)
     *  - HostingRecord + entryPoint + ProcessRecord (returns boolean)
     *
     * Do not assume argument positions. Try the layouts that carry an ApplicationInfo or a
     * ProcessRecord, but require a non-null/non-false result so a no-op/on-hold call does not
     * become a false "process started" event.
     */
    private fun readProcessStartIdentity(arguments: Array<Any?>, result: Any?): HookIdentity? {
        when (result) {
            null -> return null
            is Boolean -> if (!result) return null
            else -> Unit
        }
        readApplicationIdentity(arguments.getOrNull(1))?.let { return it }
        readProcessIdentity(arguments.getOrNull(0))?.let { return it }
        readProcessIdentity(arguments.getOrNull(2))?.let { return it }
        return null
    }

    private fun readProcessIdentity(record: Any?): HookIdentity? {
        if (record == null) return null
        val info = runCatching { bridge().getObjectField(record, "info") }.getOrNull()
        val packageName = runCatching {
            bridge().getObjectField(info, "packageName") as? String
        }.getOrNull()
        val uid = when (val raw = runCatching {
            bridge().getObjectField(info, "uid")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> null
        } ?: return null
        val userId = when (val raw = runCatching {
            bridge().getObjectField(record, "userId")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> uid / 100_000
        }
        return HookIdentityReader.of(packageName, userId)
    }

    private fun readMediaIdentity(record: Any?): HookIdentity? {
        if (record == null) return null
        val packageName = runCatching {
            bridge().callMethod(record, "getPackageName") as? String
        }.getOrNull() ?: runCatching {
            bridge().getObjectField(record, "mPackageName") as? String
        }.getOrNull()
        val userId = when (val raw = runCatching {
            bridge().callMethod(record, "getUserId")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> runCatching { bridge().getObjectField(record, "mUserId") as? Number }
                .getOrNull()?.toInt()
        }
        return HookIdentityReader.of(packageName, userId)
    }

    private fun readServiceIdentity(record: Any?): HookIdentity? {
        if (record == null) return null
        val packageName = runCatching {
            bridge().getObjectField(record, "packageName") as? String
        }.getOrNull() ?: runCatching {
            val appInfo = bridge().getObjectField(record, "appInfo")
            bridge().getObjectField(appInfo, "packageName") as? String
        }.getOrNull()
        val userId = when (val raw = runCatching {
            bridge().getObjectField(record, "userId")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> runCatching {
                val appInfo = bridge().getObjectField(record, "appInfo")
                val uid = bridge().getObjectField(appInfo, "uid") as Number
                uid.toInt() / 100_000
            }.getOrNull()
        }
        return HookIdentityReader.of(packageName, userId)
    }

    private fun logSkip(candidate: HookCandidate, reason: String) {
        val key = "${candidate.label}:$reason"
        if (skipped.putIfAbsent(key, true) == null) {
            log("candidate ${candidate.label} skipped: $reason")
        }
    }

    /** Runs on the dispatcher's HandlerThread, never under the hooked method's lock. */
    private fun sendEvent(event: HookEvent) {
        val context = systemContext ?: return
        val intent = Intent(SystemHookProtocol.ACTION_EVENT)
            .setClassName(SystemHookProtocol.APP_PACKAGE, SystemHookProtocol.SYSTEM_RECEIVER)
            .putExtra(SystemHookProtocol.EXTRA_EVENT, event.kind)
            .putExtra(SystemHookProtocol.EXTRA_TIME, event.atEpochMs)
            .putExtra(SystemHookProtocol.EXTRA_SEQ, sequence.incrementAndGet())
        event.packageName?.let { intent.putExtra(SystemHookProtocol.EXTRA_PACKAGE, it) }
        event.userId?.let { intent.putExtra(SystemHookProtocol.EXTRA_USER, it) }
        context.sendBroadcast(intent)
    }

    /**
     * Tell the app that the system hook is present. This is sent even when the switch is off, so
     * the app can distinguish "hook never loaded" from "loaded but disabled". The app replies
     * with its persisted state and an acknowledgement flag.
     */
    private fun sendLoaded(context: Context?) {
        val ctx = context ?: return
        val key = "loaded:${installedHooks()}:${availableFamilyMask()}"
        if (!loadedLimiter.allow(key, System.currentTimeMillis())) return
        safe("send loaded") {
            ctx.sendBroadcast(
                Intent(SystemHookProtocol.ACTION_LOADED)
                    .setClassName(SystemHookProtocol.APP_PACKAGE, SystemHookProtocol.SYSTEM_RECEIVER)
                    .putExtra(SystemHookProtocol.EXTRA_HOOKS, installedHooks())
                    .putExtra(SystemHookProtocol.EXTRA_AVAILABLE_FAMILIES, availableFamilyMask())
            )
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            safe("state receiver") {
                val action = intent?.action
                if (action != SystemHookProtocol.ACTION_STATE &&
                    action != SystemHookProtocol.ACTION_CONFIG
                ) return@safe
                val now = System.currentTimeMillis()
                val enabledValue = intent.getBooleanExtra(SystemHookProtocol.EXTRA_ENABLED, false)
                val families = intent.getIntExtra(
                    SystemHookProtocol.EXTRA_FAMILIES,
                    SystemHookFamily.defaultMask,
                ) and knownFamilyMask()
                val watched = parseWatched(intent.getStringExtra(SystemHookProtocol.EXTRA_WATCHED))
                val appKnowsLoaded = intent.getBooleanExtra(
                    SystemHookProtocol.EXTRA_APP_KNOWS_LOADED,
                    false,
                )
                val stateKey = "$enabledValue|$families|${watched.sorted().joinToString(",")}|$appKnowsLoaded"
                val changed = stateGate.allow(stateKey)
                if (changed) {
                    enabled.set(enabledValue)
                    if (enabledValue) lastKeyguard.set(null)
                    subscribedFamilies.set(families)
                    watchedKeys.set(watched)
                    if (stateLogLimiter.allow("state", now)) {
                        log(
                            "enabled=${enabled.get()} families=0x${subscribedFamilies.get().toString(16)} " +
                                "watched=${watchedKeys.get().size} appKnowsLoaded=$appKnowsLoaded"
                        )
                    }
                }
                // A repeated identical STATE/CONFIG must not restart the heartbeat: doing that
                // posts an immediate heartbeat, which makes the app refresh its config, which
                // arrives right back here (the 2026-11 broadcast storm).
                if (!appKnowsLoaded) sendLoaded(systemContext)
                if (enabledValue) startHeartbeat(systemContext) else stopHeartbeat()
            }
        }
    }

    private fun parseWatched(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',')
            .asSequence()
            .take(MAX_WATCHED)
            .mapNotNull { HookIdentityReader.fromKey(it)?.key }
            .toSet()
    }

    private fun installedHooks(): Int = installedCounts.values.sum()

    private fun availableFamilyMask(): Int =
        installedCounts.entries.fold(0) { result, entry ->
            if (entry.value > 0) result or entry.key.mask else result
        }

    private fun knownFamilyMask(): Int =
        SystemHookFamily.entries.fold(0) { result, family -> result or family.mask }

    private fun safe(where: String, block: () -> Unit) {
        HookFailureGuard.run(where, log = ::log, block = block)
    }

    private fun log(message: String) {
        runCatching { bridge?.log(message) }
    }

    private fun startHeartbeat(context: Context?) {
        if (context == null || !enabled.get()) {
            stopHeartbeat()
            return
        }
        // Do not reset an already-running heartbeat. Every STATE/CONFIG used to call
        // stopHeartbeat() + an immediate sendHeartbeat(), so a config sent in response to a
        // heartbeat produced another heartbeat and another config at broadcast speed.
        synchronized(heartbeatLock) {
            if (heartbeat != null) return
            val handler = Handler(Looper.getMainLooper())
            val task = object : Runnable {
                override fun run() {
                    safe("heartbeat") {
                        sendHeartbeat(context)
                        if (enabled.get()) handler.postDelayed(this, HEARTBEAT_MS)
                    }
                }
            }
            heartbeat = task
            safe("schedule heartbeat") { handler.post(task) }
        }
    }

    private fun sendHeartbeat(context: Context) {
        context.sendBroadcast(
            Intent(SystemHookProtocol.ACTION_HEARTBEAT)
                .setClassName(SystemHookProtocol.APP_PACKAGE, SystemHookProtocol.SYSTEM_RECEIVER)
                .putExtra(SystemHookProtocol.EXTRA_HOOKS, installedHooks())
                .putExtra(SystemHookProtocol.EXTRA_AVAILABLE_FAMILIES, availableFamilyMask())
        )
    }

    private fun stopHeartbeat() {
        synchronized(heartbeatLock) {
            val task = heartbeat
            heartbeat = null
            if (task == null) return
            safe("stop heartbeat") {
                Handler(Looper.getMainLooper()).removeCallbacks(task)
            }
        }
    }

    private const val HEARTBEAT_MS = 20_000L
    private const val STATE_LOG_MIN_INTERVAL_MS = 2_000L
    private const val LOADED_MIN_INTERVAL_MS = 2_000L
    private const val EVENT_QUEUE_CAPACITY = 32
    private const val PROCESS_DEDUPE_MS = 750L
    private const val MAX_WATCHED = 128
}
