package com.flowglass.demo.xposed.a11y

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.flowglass.demo.system.a11y.A11yKeepAlivePolicy
import com.flowglass.demo.system.a11y.A11yServiceCatalog
import com.flowglass.demo.xposed.BroadcastRateLimiter
import com.flowglass.demo.xposed.ContentGate
import com.flowglass.demo.xposed.HookBridge
import com.flowglass.demo.xposed.HookCallback
import com.flowglass.demo.xposed.HookFailureGuard

/**
 * system_server side of the multi-target keep-alive.
 *
 * It observes disconnect callbacks and calls the framework's own `bindLocked()` on a dedicated
 * worker thread. Every callback and posted task is wrapped in [HookFailureGuard]; nothing here
 * touches the hook's read-only event bus.
 */
internal object A11yKeepAliveHook {
    private const val CONNECTION = "com.android.server.accessibility.AccessibilityServiceConnection"
    private const val ABSTRACT_CONNECTION = "com.android.server.accessibility.AbstractAccessibilityServiceConnection"
    private const val FIELD_COMPONENT = "mComponentName"
    private const val FIELD_LOCK = "mLock"
    private const val FIELD_SERVICE = "mService"
    private const val FIELD_BOUND = "mBound"
    private const val MAX_TARGETS = 64
    private val COMPONENT = Regex("""^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+$""")

    private val configGate = ContentGate()
    private val statusLimiter = BroadcastRateLimiter(1_000L)
    private val loadedLimiter = BroadcastRateLimiter(2_000L)
    private val disconnectLimiter = BroadcastRateLimiter(1_000L)

    @Volatile private var bridge: HookBridge? = null
    @Volatile private var loader: ClassLoader? = null
    @Volatile private var context: Context? = null
    @Volatile private var receiverRegistered = false
    @Volatile private var enabled = false
    @Volatile private var targets: Set<String> = emptySet()
    @Volatile private var hookCount = 0
    @Volatile private var worker: A11yRepairWorker? = null

    fun install(hookBridge: HookBridge, classLoader: ClassLoader) {
        HookFailureGuard.run("a11y keepalive install", hookBridge::log) {
            bridge = hookBridge
            loader = classLoader
            val classes = listOf(CONNECTION, ABSTRACT_CONNECTION)
                .mapNotNull { name -> runCatching { hookBridge.findClass(name, classLoader) }.getOrNull() }
            for (clazz in classes) {
                hookCount += hookBridge.hookAllMethods(clazz, "binderDied", HookCallback { conn, _, _ ->
                    onDisconnected(conn, null)
                })
                hookCount += hookBridge.hookAllMethods(clazz, "onServiceDisconnected", HookCallback { conn, args, _ ->
                    onDisconnected(conn, args.firstOrNull() as? ComponentName)
                })
                hookCount += hookBridge.hookAllMethods(clazz, "onServiceConnected", HookCallback { conn, args, _ ->
                    (args.firstOrNull() as? ComponentName)?.let { onConnected(conn, it) }
                })
            }
            val handler = Handler(
                HandlerThread("flowglass-a11y-keepalive").apply { start() }.looper,
            )
            worker = A11yRepairWorker(
                ops = connectionOps(hookBridge),
                schedule = { delayMs, task -> handler.postDelayed(task, delayMs) },
                now = SystemClock::elapsedRealtime,
                log = ::log,
                onStatus = ::sendStatus,
            )
            hookBridge.log("a11y keepalive hooks=$hookCount")
            bootstrapContext(hookBridge, classLoader)
        }
    }

    fun supported(): Boolean = hookCount > 0

    /**
     * LSPosed can load a module after `ActivityThread.systemMain()` has already run (observed on
     * Android 14 + KernelSU), so a systemMain hook alone never yields a Context and the state
     * broadcast never leaves system_server. Try immediately, then retry from every late but
     * guaranteed system_server callback.
     */
    private fun bootstrapContext(b: HookBridge, classLoader: ClassLoader) {
        val retry = {
            HookFailureGuard.post(
                handler = Handler(Looper.getMainLooper()),
                where = "a11y system context",
                log = b::log,
            ) { ensureContext()?.let(::sendLoaded) }
        }
        retry()
        for ((className, method) in listOf(
            "android.app.ActivityThread" to "systemMain",
            "com.android.server.SystemServer" to "startBootstrapServices",
            "com.android.server.am.ActivityManagerService" to "systemReady",
        )) {
            runCatching {
                val clazz = b.findClass(className, classLoader)
                b.hookAllMethods(clazz, method, HookCallback { _, _, _ -> retry() })
            }.onFailure {
                b.log("a11y bootstrap $className#$method unavailable: ${it.javaClass.simpleName}")
            }
        }
    }

    private fun onConnected(connection: Any?, component: ComponentName) {
        HookFailureGuard.run("a11y connected", ::log) {
            sendStatus(component.flattenToString(), "active", "")
        }
    }

    private fun onDisconnected(connection: Any?, explicit: ComponentName?) {
        val b = bridge ?: return
        HookFailureGuard.run("a11y disconnected", b::log) {
            val component = explicit ?: readComponent(connection) ?: return@run
            val key = component.flattenToString()
            if (!disconnectLimiter.allow(key, SystemClock.elapsedRealtime())) return@run
            if (key !in targets) return@run
            sendStatus(key, "disconnected", "")
            val target = connection ?: return@run
            worker?.onDisconnected(key, target)
        }
    }

    private fun readComponent(connection: Any?): ComponentName? = runCatching {
        bridge?.getObjectField(connection, FIELD_COMPONENT) as? ComponentName
    }.getOrNull()

    private fun connectionOps(b: HookBridge): A11yConnectionOps = object : A11yConnectionOps {
        override fun componentOf(connection: Any): String? = readComponent(connection)?.flattenToString()

        override fun isAlive(connection: Any): Boolean = isAliveWith(b, connection)

        override fun decide(component: String, connection: Any, attempts: Int): A11yKeepAlivePolicy.Result {
            val ctx = ensureContext()
                ?: return A11yKeepAlivePolicy.Result(false, "no_context")
            val pkg = component.substringBefore('/')
            val master = runCatching {
                Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            }.getOrDefault(false)
            val stillEnabled = component in A11yServiceCatalog.enabledComponents(ctx)
            val stopped = runCatching {
                (ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
            }.getOrDefault(true)
            return A11yKeepAlivePolicy.decide(
                isTarget = component in targets,
                keepAliveEnabled = enabled,
                a11yMasterEnabled = master,
                componentEnabled = stillEnabled,
                appStopped = stopped,
                alreadyBound = isAliveWith(b, connection),
                attempts = attempts,
            )
        }

        override fun bind(connection: Any): Boolean = bindLocked(b, connection)

        override fun onRepaired(component: String) = sendStatus(component, "repaired", "")
    }

    /**
     * Calls the framework's own binding path. `bindLocked` expects the connection lock to be held;
     * when the framework still thinks it is bound to a dead binder, drop that stale binding first.
     */
    private fun bindLocked(b: HookBridge, connection: Any): Boolean {
        val lock = runCatching { b.getObjectField(connection, FIELD_LOCK) }.getOrNull() ?: return false
        synchronized(lock) {
            if (isAliveWith(b, connection)) return true
            val stillBound = runCatching { b.getObjectField(connection, FIELD_BOUND) as? Boolean ?: false }
                .getOrDefault(false)
            if (stillBound) runCatching { b.callMethod(connection, "unbindLocked") }
            return runCatching { b.callMethod(connection, "bindLocked") != false }.getOrDefault(false)
        }
    }

    private fun isAliveWith(b: HookBridge, connection: Any): Boolean =
        runCatching { b.getObjectField(connection, FIELD_SERVICE) }.getOrNull() != null

    private fun ensureContext(): Context? {
        context?.let { return it }
        val b = bridge ?: return null
        val cl = loader ?: return null
        val captured = runCatching {
            val threadClass = b.findClass("android.app.ActivityThread", cl)
            val thread = b.callStaticMethod(threadClass, "currentActivityThread")
            b.callMethod(thread, "getSystemContext") as? Context
        }.getOrNull() ?: return null
        context = captured
        registerConfigReceiver(captured)
        return captured
    }

    private fun registerConfigReceiver(ctx: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter(A11yKeepAliveProtocol.ACTION_CONFIG)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(configReceiver, filter, A11yKeepAliveProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(configReceiver, filter, A11yKeepAliveProtocol.PERMISSION, null)
        }
        receiverRegistered = true
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            HookFailureGuard.run("a11y config", ::log) {
                if (intent?.action != A11yKeepAliveProtocol.ACTION_CONFIG) return@run
                val newEnabled = intent.getBooleanExtra(A11yKeepAliveProtocol.EXTRA_ENABLED, false)
                val newTargets = parseTargets(intent.getStringExtra(A11yKeepAliveProtocol.EXTRA_TARGETS))
                val appKnowsLoaded = intent.getBooleanExtra(A11yKeepAliveProtocol.EXTRA_APP_KNOWS_LOADED, false)
                if (configGate.allow("$newEnabled|${newTargets.sorted()}|$appKnowsLoaded")) {
                    enabled = newEnabled
                    targets = newTargets
                    log("config enabled=$newEnabled targets=${newTargets.size}")
                }
                if (!appKnowsLoaded) ensureContext()?.let(::sendLoaded)
            }
        }
    }

    private fun parseTargets(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split('\n').asSequence().filter { COMPONENT.matches(it) }.take(MAX_TARGETS).toSet()
    }

    private fun sendLoaded(ctx: Context) {
        if (!loadedLimiter.allow("loaded:$hookCount", System.currentTimeMillis())) return
        runCatching {
            ctx.sendBroadcast(
                Intent(A11yKeepAliveProtocol.ACTION_LOADED)
                    .setClassName(A11yKeepAliveProtocol.APP_PACKAGE, A11yKeepAliveProtocol.RECEIVER)
                    .putExtra(A11yKeepAliveProtocol.EXTRA_HOOKS, hookCount)
                    .putExtra(A11yKeepAliveProtocol.EXTRA_SUPPORTED, hookCount > 0),
            )
        }
    }

    internal fun sendStatus(component: String, state: String, reason: String) {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        if (!statusLimiter.allow("$component|$state", now)) return
        runCatching {
            ctx.sendBroadcast(
                Intent(A11yKeepAliveProtocol.ACTION_STATUS)
                    .setClassName(A11yKeepAliveProtocol.APP_PACKAGE, A11yKeepAliveProtocol.RECEIVER)
                    .putExtra(A11yKeepAliveProtocol.EXTRA_COMPONENT, component)
                    .putExtra(A11yKeepAliveProtocol.EXTRA_STATE, state)
                    .putExtra(A11yKeepAliveProtocol.EXTRA_REASON, reason)
                    .putExtra(A11yKeepAliveProtocol.EXTRA_AT, now),
            )
        }
    }

    private fun log(message: String) {
        runCatching { bridge?.log(message) }
    }
}
