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
 * candidate "resumed activity" methods and send a protected broadcast; nothing is replaced or
 * mutated. Installation and every callback are wrapped so an unexpected ROM layout can never
 * escape into system_server.
 */
internal object SystemHook {
    private val enabled = AtomicBoolean(false)
    private val lastIdentity = AtomicReference<String?>(null)
    @Volatile private var systemContext: Context? = null
    @Volatile private var receiverRegistered = false
    private var heartbeat: Runnable? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        safe("install") {
            hookSystemContext(lpparam.classLoader)
            hookForegroundCandidates(lpparam.classLoader)
        }
    }

    private fun hookSystemContext(classLoader: ClassLoader) {
        val activityThread = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
        XposedBridge.hookAllMethods(activityThread, "systemMain", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                safe("register state receiver") {
                    Handler(Looper.getMainLooper()).post { registerStateReceiver(classLoader) }
                }
            }
        })
        // Try immediately as well: on some LSPosed versions handleLoadPackage is already running
        // after the system context exists, so systemMain may have been called before our hook.
        safe("register state receiver (initial)") {
            Handler(Looper.getMainLooper()).post { registerStateReceiver(classLoader) }
        }
        // Some ROMs install modules too late for systemMain; startBootstrapServices runs after
        // the system context exists and gives us a second, harmless registration attempt.
        runCatching {
            val systemServer = XposedHelpers.findClass("com.android.server.SystemServer", classLoader)
            XposedBridge.hookAllMethods(systemServer, "startBootstrapServices", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    safe("register state receiver (bootstrap)") {
                        Handler(Looper.getMainLooper()).post { registerStateReceiver(classLoader) }
                    }
                }
            })
        }.onFailure { log("bootstrap hook unavailable: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            val ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            XposedBridge.hookAllMethods(ams, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    safe("register state receiver (ams)") {
                        Handler(Looper.getMainLooper()).post { registerStateReceiver(classLoader) }
                    }
                }
            })
        }.onFailure { log("AMS systemReady hook unavailable: ${it.javaClass.simpleName}: ${it.message}") }
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
        val candidates = listOf(
            "com.android.server.wm.ActivityTaskManagerService" to "setResumedActivity",
            "com.android.server.wm.ActivityTaskManagerService" to "updateResumedActivity",
            "com.android.server.am.ActivityManagerService" to "setResumedActivity",
            "com.android.server.wm.ActivityTaskSupervisor" to "realStartActivityLocked",
            "com.android.server.wm.RootWindowContainer" to "onActivityAdded",
        )
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                safe("foreground event") { observe(param?.args) }
            }
        }
        var installed = 0
        for ((className, methodName) in candidates) {
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

    private fun observe(arguments: Array<Any?>?) {
        if (!enabled.get()) return
        val context = systemContext ?: return
        for (argument in arguments.orEmpty()) {
            val packageName = packageOf(argument) ?: continue
            if (!PACKAGE_PATTERN.matches(packageName)) continue
            val userId = userOf(argument) ?: 0
            val identity = if (userId == 0) packageName else "$packageName@$userId"
            if (lastIdentity.get() == identity) return
            lastIdentity.set(identity)
            val intent = Intent(SystemHookProtocol.ACTION_FOREGROUND)
                .setPackage(SystemHookProtocol.APP_PACKAGE)
                .putExtra(SystemHookProtocol.EXTRA_PACKAGE, packageName)
                .putExtra(SystemHookProtocol.EXTRA_USER, userId)
                .putExtra(SystemHookProtocol.EXTRA_TIME, System.currentTimeMillis())
            context.sendBroadcast(intent)
            return
        }
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
        try {
            block()
        } catch (failure: Throwable) {
            // Never propagate into system_server, whatever the ROM's method signatures are.
            log("$where failed: ${failure.javaClass.simpleName}: ${failure.message}")
        }
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
                    context.sendBroadcast(
                        Intent(SystemHookProtocol.ACTION_HEARTBEAT)
                            .setPackage(SystemHookProtocol.APP_PACKAGE)
                    )
                }
                if (enabled.get()) handler.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeat = task
        handler.post(task)
    }

    private fun stopHeartbeat() {
        heartbeat?.let { task -> Handler(Looper.getMainLooper()).removeCallbacks(task) }
        heartbeat = null
    }

    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    private const val HEARTBEAT_MS = 20_000L
}
