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
    private var heartbeat: Runnable? = null
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
            hookSystemContext(lpparam.classLoader)
            hookForegroundCandidates(lpparam.classLoader)
        }
    }

    private fun hookSystemContext(classLoader: ClassLoader) {
        val activityThread = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
        XposedBridge.hookAllMethods(activityThread, "systemMain", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                postToMain("register state receiver (systemMain)", classLoader)
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
                    postToMain("register state receiver (bootstrap)", classLoader)
                }
            })
        }.onFailure { log("bootstrap hook unavailable: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            val ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            XposedBridge.hookAllMethods(ams, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    postToMain("register state receiver (ams)", classLoader)
                }
            })
        }.onFailure { log("AMS systemReady hook unavailable: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Schedules on the main Looper; the posted Runnable itself is protected, not just post(). */
    private fun postToMain(where: String, classLoader: ClassLoader) {
        safe("post $where") {
            Handler(Looper.getMainLooper()).post {
                safe(where) { registerStateReceiver(classLoader) }
            }
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
        if (enabled.get()) startHeartbeat(context)
    }

    private fun hookForegroundCandidates(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                safe("foreground event") { observe(param?.args) }
            }
        }
        var installed = 0
        for ((className, methodName) in SystemHookCandidates.resumed) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                val methods = XposedBridge.hookAllMethods(clazz, methodName, hook)
                if (methods.isNotEmpty()) installed += methods.size
            } catch (failure: Throwable) {
                // Method names differ across Android versions; try the next candidate.
                log("hook $className#$methodName unavailable: ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
        log("foreground hooks installed=$installed")
    }

    /**
     * Runs on the hooked system-server thread while WMS/AMS locks may be held. It must not send a
     * broadcast or call into ActivityManager here; only copy plain data into the bounded queue.
     */
    private fun observe(arguments: Array<Any?>?) {
        if (!enabled.get()) return
        for (argument in arguments.orEmpty()) {
            val packageName = packageOf(argument) ?: continue
            if (!PACKAGE_PATTERN.matches(packageName)) continue
            val userId = userOf(argument) ?: 0
            val identity = if (userId == 0) packageName else "$packageName@$userId"
            if (lastIdentity.get() == identity) return
            lastIdentity.set(identity)
            dispatcher.enqueue(
                HookForegroundEvent(
                    packageName = packageName,
                    userId = userId,
                    atEpochMs = System.currentTimeMillis(),
                )
            )
            return
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

    private fun packageOf(value: Any?): String? {
        if (value == null) return null
        for (name in listOf("getPackageName", "packageName", "mPackageName")) {
            runCatching { XposedHelpers.callMethod(value, name) as? String }.getOrNull()?.let { return it }
            runCatching { XposedHelpers.getObjectField(value, name) as? String }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun userOf(value: Any?): Int? {
        if (value == null) return null
        for (name in listOf("getUserId", "userId", "mUserId")) {
            val raw = runCatching { XposedHelpers.callMethod(value, name) }.getOrNull()
                ?: runCatching { XposedHelpers.getObjectField(value, name) }.getOrNull()
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            safe("state receiver") {
                if (intent?.action != SystemHookProtocol.ACTION_STATE) return@safe
                enabled.set(intent.getBooleanExtra(SystemHookProtocol.EXTRA_ENABLED, false))
                log("enabled=${enabled.get()}")
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

    /**
     * Only methods that represent a real resumed-activity transition are hooked; the candidate
     * list lives in [SystemHookCandidates] so the exclusion is structurally testable.
     */
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    private const val HEARTBEAT_MS = 20_000L
    private const val EVENT_QUEUE_CAPACITY = 32
}
