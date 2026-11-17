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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * System-server side of the LSPosed integration. It is deliberately read-only: hooks only observe
 * candidate "resumed activity" methods and enqueue package/user data; nothing is replaced or
 * mutated. The actual broadcast is sent by [SystemHookDispatcher] on its own HandlerThread, after
 * the callback has returned and outside the WindowManager/ActivityManager locks held by the
 * hooked methods.
 *
 * Every callback and every posted Runnable is routed through [HookFailureGuard]: an unexpected
 * ROM layout or SecurityException is logged and can never escape into system_server.
 */
internal object SystemHook {
    private val enabled = AtomicBoolean(false)
    private val lastIdentity = AtomicReference<String?>(null)
    @Volatile private var systemContext: Context? = null
    @Volatile private var receiverRegistered = false
    @Volatile private var installedHooks = 0
    private var heartbeat: Runnable? = null
    private val skipped = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val dispatcher: SystemHookDispatcher by lazy {
        SystemHookDispatcher(
            capacity = EVENT_QUEUE_CAPACITY,
            log = ::log,
            sender = ::sendForeground,
        )
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        safe("install") {
            dispatcher.warmUp()
            // Install detection points before any receiver can request a heartbeat, so the first
            // heartbeat always reports the real installed count (including zero).
            hookForegroundCandidates(lpparam.classLoader)
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
        val filter = IntentFilter(SystemHookProtocol.ACTION_STATE)
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
        var installed = 0
        val active = mutableListOf<String>()
        for (candidate in SystemHookCandidates.resumed) {
            var classFound = false
            try {
                val clazz = XposedHelpers.findClass(candidate.className, classLoader)
                classFound = true
                val methods = XposedBridge.hookAllMethods(clazz, candidate.methodName, foregroundHook(candidate))
                val count = methods.size
                if (count > 0) {
                    installed += count
                    active += "${candidate.label}($count)"
                }
                log("candidate ${candidate.label}: class=found hooks=$count")
            } catch (failure: Throwable) {
                log(
                    "candidate ${candidate.label}: class=${if (classFound) "found" else "missing"} " +
                        "hooks=0 (${failure.javaClass.simpleName}: ${failure.message})"
                )
            }
        }
        installedHooks = installed
        log("foreground hooks installed=$installed active=${active.ifEmpty { listOf("none") }.joinToString(",")}")
    }

    private fun foregroundHook(candidate: HookCandidate): XC_MethodHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam?) {
            safe("foreground event ${candidate.label}") { observe(candidate, param) }
        }
    }

    /**
     * Runs on the hooked system-server thread while WMS/AMS locks may be held. It must not send a
     * broadcast or call into ActivityManager here; only copy plain data into the bounded queue.
     */
    private fun observe(candidate: HookCandidate, param: XC_MethodHook.MethodHookParam?) {
        if (!enabled.get()) return
        val arguments = param?.args ?: return
        val record = when (candidate.source) {
            HookEventSource.FIRST_ARGUMENT_ACTIVITY -> arguments.getOrNull(0)
            HookEventSource.THIS_ACTIVITY_STATE -> {
                if (!isResumedState(arguments.getOrNull(0))) return
                param.thisObject
            }
        } ?: run {
            logSkip(candidate, "activity record is null")
            return
        }
        val identity = readIdentity(record) ?: run {
            logSkip(candidate, "packageName/mUserId unavailable")
            return
        }
        val key = if (identity.userId == 0) identity.packageName else "${identity.packageName}@${identity.userId}"
        if (lastIdentity.get() == key) return
        lastIdentity.set(key)
        dispatcher.enqueue(
            HookForegroundEvent(
                packageName = identity.packageName,
                userId = identity.userId,
                atEpochMs = System.currentTimeMillis(),
            )
        )
    }

    private fun isResumedState(state: Any?): Boolean {
        if (state == null) return false
        val name = runCatching { XposedHelpers.callMethod(state, "name") as? String }.getOrNull()
        return name == "RESUMED"
    }

    private fun readIdentity(record: Any?): HookIdentity? {
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

    private fun logSkip(candidate: HookCandidate, reason: String) {
        val key = "${candidate.label}:$reason"
        if (skipped.putIfAbsent(key, true) == null) {
            log("candidate ${candidate.label} skipped: $reason")
        }
    }

    /** Runs on the dispatcher's HandlerThread, never under the hooked method's lock. */
    private fun sendForeground(event: HookForegroundEvent) {
        val context = systemContext ?: return
        val intent = Intent(SystemHookProtocol.ACTION_FOREGROUND)
            .setClassName(SystemHookProtocol.APP_PACKAGE, SystemHookProtocol.SYSTEM_RECEIVER)
            .putExtra(SystemHookProtocol.EXTRA_PACKAGE, event.packageName)
            .putExtra(SystemHookProtocol.EXTRA_USER, event.userId)
            .putExtra(SystemHookProtocol.EXTRA_TIME, event.atEpochMs)
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
                    .putExtra(SystemHookProtocol.EXTRA_HOOKS, installedHooks)
            )
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            safe("state receiver") {
                if (intent?.action != SystemHookProtocol.ACTION_STATE) return@safe
                enabled.set(intent.getBooleanExtra(SystemHookProtocol.EXTRA_ENABLED, false))
                val appKnowsLoaded = intent.getBooleanExtra(SystemHookProtocol.EXTRA_APP_KNOWS_LOADED, false)
                log("enabled=${enabled.get()} appKnowsLoaded=$appKnowsLoaded")
                // The app may have started before this receiver was registered. Its state
                // broadcast then carries appKnowsLoaded=false; replay the loaded announcement
                // once. Its acknowledgement carries true, so this cannot loop.
                if (!appKnowsLoaded) sendLoaded(systemContext)
                if (enabled.get()) startHeartbeat(systemContext) else stopHeartbeat()
            }
        }
    }

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
                .putExtra(SystemHookProtocol.EXTRA_HOOKS, installedHooks)
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
}
