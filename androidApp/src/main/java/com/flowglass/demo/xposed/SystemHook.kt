package com.flowglass.demo.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
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
    private val lastProcessEvent = ConcurrentHashMap<String, Long>()
    private val installedCounts = ConcurrentHashMap<SystemHookFamily, Int>()
    private val sequence = AtomicLong(0L)
    @Volatile private var systemContext: Context? = null
    @Volatile private var receiverRegistered = false
    private var heartbeat: Runnable? = null
    private val skipped = ConcurrentHashMap<String, Boolean>()
    private val dispatcher: SystemHookDispatcher by lazy {
        SystemHookDispatcher(
            capacity = EVENT_QUEUE_CAPACITY,
            log = ::log,
            sender = ::sendEvent,
        )
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        safe("install") {
            dispatcher.warmUp()
            // Install detection points before any receiver can request a heartbeat, so the first
            // heartbeat always reports the real installed count (including zero).
            hookForegroundCandidates(lpparam.classLoader)
            hookProcessCandidates(lpparam.classLoader)
            hookSystemContext(lpparam.classLoader)
        }
    }

    private fun hookSystemContext(classLoader: ClassLoader) {
        val activityThread = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
        XposedBridge.hookAllMethods(activityThread, "systemMain", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                safe("systemMain callback") {
                    postToMain("register state receiver (systemMain)", classLoader)
                }
            }
        })
        // Try immediately as well: on some LSPosed versions handleLoadPackage is already running
        // after the system context exists, so systemMain may have been called before our hook.
        postToMain("register state receiver (initial)", classLoader)
        // Some ROMs install modules too late for systemMain; startBootstrapServices runs after
        // the system context exists and gives us a second, harmless registration attempt.
        runCatching {
            val systemServer = XposedHelpers.findClass("com.android.server.SystemServer", classLoader)
            XposedBridge.hookAllMethods(systemServer, "startBootstrapServices", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    safe("bootstrap callback") {
                        postToMain("register state receiver (bootstrap)", classLoader)
                    }
                }
            })
        }.onFailure { log("bootstrap hook unavailable: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            val ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            XposedBridge.hookAllMethods(ams, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    safe("AMS systemReady callback") {
                        postToMain("register state receiver (ams)", classLoader)
                    }
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
            val threadClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
            val thread = XposedHelpers.callStaticMethod(threadClass, "currentActivityThread")
            XposedHelpers.callMethod(thread, "getSystemContext") as? Context
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

    /**
     * Hooks every candidate once and records its own count. A family can contain candidates from
     * several classes; the total is what makes that family available to the app.
     */
    private fun hookCandidates(
        classLoader: ClassLoader,
        candidates: List<HookCandidate>,
        hookFactory: (HookCandidate) -> XC_MethodHook,
        family: SystemHookFamily?,
    ) {
        for (candidate in candidates) {
            var classFound = false
            try {
                val clazz = XposedHelpers.findClass(candidate.className, classLoader)
                classFound = true
                val methods = XposedBridge.hookAllMethods(clazz, candidate.methodName, hookFactory(candidate))
                val count = methods.size
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

    private fun foregroundHook(candidate: HookCandidate): XC_MethodHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam?) {
            safe("foreground event ${candidate.label}") { observeForeground(candidate, param) }
        }
    }

    private fun processHook(candidate: HookCandidate): XC_MethodHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam?) {
            safe("process event ${candidate.label}") { observeProcess(candidate, param) }
        }
    }

    /**
     * Runs on the hooked system-server thread while WMS/AMS locks may be held. It must not send a
     * broadcast or call into ActivityManager here; only copy plain data into the bounded queue.
     */
    private fun observeForeground(candidate: HookCandidate, param: XC_MethodHook.MethodHookParam?) {
        if (!canEmit(SystemHookFamily.FOREGROUND)) return
        val arguments = param?.args ?: return
        val record = when (candidate.source) {
            HookEventSource.FIRST_ARGUMENT_ACTIVITY -> arguments.getOrNull(0)
            HookEventSource.THIS_ACTIVITY_STATE -> {
                if (!isResumedState(arguments.getOrNull(0))) return
                param.thisObject
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

    private fun observeProcess(candidate: HookCandidate, param: XC_MethodHook.MethodHookParam?) {
        if (!canEmit(candidate.family)) return
        val arguments = param?.args ?: return
        val identity = when (candidate.source) {
            HookEventSource.SECOND_ARGUMENT_APPLICATION -> {
                // startProcessLocked returns null when the process was not actually started.
                if (param.result == null) {
                    logSkip(candidate, "startProcessLocked returned null")
                    return
                }
                readApplicationIdentity(arguments.getOrNull(1))
            }
            HookEventSource.FIRST_ARGUMENT_PROCESS -> readProcessIdentity(arguments.getOrNull(0))
            HookEventSource.SECOND_ARGUMENT_PROCESS -> readProcessIdentity(arguments.getOrNull(1))
            HookEventSource.CLEANUP_PROCESS_RECORD -> {
                // A restarting app is not an exit; it immediately gets a new process.
                if (arguments.getOrNull(2) == true) return
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

    private fun emit(family: SystemHookFamily, kind: String, identity: HookIdentity) {
        if (!canEmit(family)) return
        dispatcher.enqueue(
            HookEvent(
                family = family,
                kind = kind,
                packageName = identity.packageName,
                userId = identity.userId,
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
        val name = runCatching { XposedHelpers.callMethod(state, "name") as? String }.getOrNull()
        return name == "RESUMED"
    }

    private fun readActivityIdentity(record: Any?): HookIdentity? {
        if (record == null) return null
        val packageName = runCatching {
            XposedHelpers.getObjectField(record, "packageName") as? String
        }.getOrNull()
        val userId = when (val raw = runCatching {
            XposedHelpers.getObjectField(record, "mUserId")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> null
        }
        return HookIdentityReader.of(packageName, userId)
    }

    private fun readApplicationIdentity(info: Any?): HookIdentity? {
        if (info == null) return null
        val packageName = runCatching {
            XposedHelpers.getObjectField(info, "packageName") as? String
        }.getOrNull()
        val uid = when (val raw = runCatching {
            XposedHelpers.getObjectField(info, "uid")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> null
        } ?: return null
        return HookIdentityReader.of(packageName, uid / 100_000)
    }

    private fun readProcessIdentity(record: Any?): HookIdentity? {
        if (record == null) return null
        val info = runCatching { XposedHelpers.getObjectField(record, "info") }.getOrNull()
        val packageName = runCatching {
            XposedHelpers.getObjectField(info, "packageName") as? String
        }.getOrNull()
        val uid = when (val raw = runCatching {
            XposedHelpers.getObjectField(info, "uid")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> null
        } ?: return null
        val userId = when (val raw = runCatching {
            XposedHelpers.getObjectField(record, "userId")
        }.getOrNull()) {
            is Number -> raw.toInt()
            else -> uid / 100_000
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
                enabled.set(intent.getBooleanExtra(SystemHookProtocol.EXTRA_ENABLED, false))
                subscribedFamilies.set(
                    intent.getIntExtra(SystemHookProtocol.EXTRA_FAMILIES, SystemHookFamily.defaultMask) and
                        knownFamilyMask()
                )
                watchedKeys.set(parseWatched(intent.getStringExtra(SystemHookProtocol.EXTRA_WATCHED)))
                val appKnowsLoaded = intent.getBooleanExtra(SystemHookProtocol.EXTRA_APP_KNOWS_LOADED, false)
                log(
                    "enabled=${enabled.get()} families=0x${subscribedFamilies.get().toString(16)} " +
                        "watched=${watchedKeys.get().size} appKnowsLoaded=$appKnowsLoaded"
                )
                // The app may have started before this receiver was registered. Its state
                // broadcast then carries appKnowsLoaded=false; replay the loaded announcement
                // once. Its acknowledgement carries true, so this cannot loop.
                if (!appKnowsLoaded) sendLoaded(systemContext)
                if (enabled.get()) startHeartbeat(systemContext) else stopHeartbeat()
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
        runCatching { XposedBridge.log("FlowGlass/SystemHook: $message") }
    }

    private fun startHeartbeat(context: Context?) {
        if (context == null) return
        stopHeartbeat()
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

    private fun sendHeartbeat(context: Context) {
        context.sendBroadcast(
            Intent(SystemHookProtocol.ACTION_HEARTBEAT)
                .setClassName(SystemHookProtocol.APP_PACKAGE, SystemHookProtocol.SYSTEM_RECEIVER)
                .putExtra(SystemHookProtocol.EXTRA_HOOKS, installedHooks())
                .putExtra(SystemHookProtocol.EXTRA_AVAILABLE_FAMILIES, availableFamilyMask())
        )
    }

    private fun stopHeartbeat() {
        val task = heartbeat
        heartbeat = null
        if (task == null) return
        safe("stop heartbeat") {
            Handler(Looper.getMainLooper()).removeCallbacks(task)
        }
    }

    private const val HEARTBEAT_MS = 20_000L
    private const val EVENT_QUEUE_CAPACITY = 32
    private const val PROCESS_DEDUPE_MS = 750L
    private const val MAX_WATCHED = 128
}
