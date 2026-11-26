package com.flowglass.demo.xposed.a11y

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import com.flowglass.demo.xposed.BroadcastRateLimiter
import com.flowglass.demo.xposed.ContentGate
import com.flowglass.demo.xposed.HookBridge
import com.flowglass.demo.xposed.HookCallback
import com.flowglass.demo.xposed.HookFailureGuard

/**
 * system_server side of the multi-target keep-alive.
 *
 * It only observes disconnect callbacks and (in a later PR) calls the framework's own
 * `bindLocked()`. Every callback and posted task is wrapped in [HookFailureGuard]; nothing here
 * touches the hook's read-only event bus.
 */
internal object A11yKeepAliveHook {
    private const val CONNECTION = "com.android.server.accessibility.AccessibilityServiceConnection"
    private const val ABSTRACT_CONNECTION = "com.android.server.accessibility.AbstractAccessibilityServiceConnection"
    private const val FIELD_COMPONENT = "mComponentName"
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
            hookBridge.log("a11y keepalive hooks=$hookCount")
            runCatching {
                val activityThread = hookBridge.findClass("android.app.ActivityThread", classLoader)
                hookBridge.hookAllMethods(activityThread, "systemMain", HookCallback { _, _, _ ->
                    HookFailureGuard.run("a11y system context", hookBridge::log) {
                        ensureContext()?.let(::sendLoaded)
                    }
                })
            }
        }
    }

    fun supported(): Boolean = hookCount > 0

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
            // PR 4 replaces this line with worker.onDisconnected(key, connection).
        }
    }

    private fun readComponent(connection: Any?): ComponentName? = runCatching {
        bridge?.getObjectField(connection, FIELD_COMPONENT) as? ComponentName
    }.getOrNull()

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
