package com.flowglass.demo.xposed.method

import android.content.Context
import android.os.Process
import android.os.UserHandle
import com.flowglass.demo.xposed.HookBridge
import com.flowglass.engine.methodhook.MethodHookRuntimeConfig
import com.flowglass.engine.methodhook.MethodHookRuntimeJson
import java.lang.reflect.Method

/**
 * Target-process bootstrap. It hooks Application#attach before app code runs, then installs only
 * the hooks whose package/user match this process. system_server, SystemUI and the module's own
 * package are rejected before any configuration is trusted.
 */
internal object MethodHookTargetBootstrap {
    private const val PREF_KEY = "runtime"
    private val hookedLoaders = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun installOnApplicationAttach(
        bridge: HookBridge,
        classLoader: ClassLoader,
        packageName: String,
    ) {
        if (isExcluded(packageName)) return
        if (!hasConfiguredHook(bridge, packageName)) return
        val lock = "${System.identityHashCode(classLoader)}|$packageName"
        if (!hookedLoaders.add(lock)) return
        val application = try {
            Class.forName("android.app.Application", false, classLoader)
        } catch (_: Throwable) {
            return
        }
        val attach = runCatching {
            application.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }
        }.getOrNull() ?: return
        if (!bridge.hookMethod(attach, com.flowglass.demo.xposed.HookCallback { thisObject, args, _ ->
                val context = args.firstOrNull() as? Context ?: return@HookCallback
                val userId = MethodHookUser.idOfCurrentUser()
                val processName = currentProcessName(context)
                val config = readConfig(bridge) ?: return@HookCallback
                if (config.hooks.none {
                        it.spec.enabled && it.spec.packageName == context.packageName && it.spec.userId == userId
                    }
                ) return@HookCallback
                val client = MethodHookBinderClient(
                    context = context,
                    packageName = context.packageName,
                    userId = userId,
                    processName = processName,
                    tokenFor = { id -> config.hooks.firstOrNull { it.spec.id == id }?.token },
                )
                MethodHookInstaller.install(
                    bridge = bridge,
                    classLoader = classLoader,
                    packageName = context.packageName,
                    userId = userId,
                    processName = processName,
                    config = config,
                    emitter = client,
                )
            })
        ) {
            hookedLoaders.remove(lock)
        }
    }

    private fun hasConfiguredHook(bridge: HookBridge, packageName: String): Boolean {
        val config = readConfig(bridge) ?: return false
        val userId = MethodHookUser.idOfCurrentUser()
        return config.hooks.any { it.spec.enabled && it.spec.packageName == packageName && it.spec.userId == userId }
    }

    private fun readConfig(bridge: HookBridge): MethodHookRuntimeConfig? {
        val raw = bridge.readPreferences(MethodHookPrefs.NAME)[PREF_KEY] ?: return null
        return runCatching { MethodHookRuntimeJson.decode(raw) }.getOrNull()
    }

    private fun currentProcessName(context: Context): String {
        val direct = runCatching { Context::class.java.getMethod("getProcessName").invoke(context) as? String }
            .getOrNull()
        if (!direct.isNullOrBlank()) return direct
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            current?.let { activityThread.getMethod("getProcessName").invoke(it) as? String }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: context.packageName
    }

    private fun isExcluded(packageName: String): Boolean =
        packageName == "android" ||
            packageName == "com.flowglass.demo" ||
            packageName == "com.android.systemui"
}
