package com.flowglass.demo.xposed

import android.annotation.SuppressLint
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import com.flowglass.demo.xposed.method.MethodHookTargetBootstrap

/**
 * libxposed API 101 entry. The module app itself is not injected by modern frameworks, so this
 * entry does not try to replace ModuleStatus; the app process learns activation from
 * XposedServiceHelper instead.
 *
 * Only system_server installs system hooks here. Target-app method hooks are added in a later PR;
 * they must not run in the module's own package or in system_server/SystemUI.
 */
@SuppressLint("NewApi") // libxposed API101 only runs on Android Q+; minSdk 26 is for the app UI.
class Api101Entry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        runCatching {
            log(Log.INFO, TAG, "onModuleLoaded process=${param.processName} api=$apiVersion")
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        runCatching {
            SystemHook.install(Api101HookBridge(this, param.classLoader), param.classLoader)
        }.onFailure { failure ->
            runCatching {
                log(Log.ERROR, TAG, "system hook install failed: ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
        // Independent and failure-isolated: a broken keep-alive must not disable the read-only hook.
        runCatching {
            com.flowglass.demo.xposed.a11y.A11yKeepAliveHook
                .install(Api101HookBridge(this, param.classLoader), param.classLoader)
        }.onFailure { failure ->
            runCatching { log(Log.WARN, TAG, "a11y keepalive install failed: ${failure.javaClass.simpleName}") }
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // Kept as an explicit marker for the next PR: target-app method hooks are installed from
        // onPackageLoaded via the Application#attach bootstrap, never for the system packages.
        runCatching {
            log(Log.DEBUG, TAG, "onPackageReady package=${param.packageName} first=${param.isFirstPackage}")
        }
    }

    override fun onPackageLoaded(param: io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam) {
        val packageName = param.packageName
        if (packageName == "android" || packageName == "com.flowglass.demo" || packageName == "com.android.systemui") {
            return
        }
        runCatching {
            MethodHookTargetBootstrap.installOnApplicationAttach(
                Api101HookBridge(this, param.defaultClassLoader),
                param.defaultClassLoader,
                packageName,
            )
        }.onFailure { failure ->
            runCatching { log(Log.WARN, TAG, "method hook bootstrap failed: ${failure.javaClass.simpleName}") }
        }
    }

    private companion object {
        const val TAG = "FlowGlass/Api101"
    }
}
