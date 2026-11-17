package com.flowglass.demo.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed API 101 entry. The module app itself is not injected by modern frameworks, so this
 * entry does not try to replace ModuleStatus; the app process learns activation from
 * XposedServiceHelper instead.
 *
 * Only system_server installs system hooks here. Target-app method hooks are added in a later PR;
 * they must not run in the module's own package or in system_server/SystemUI.
 */
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
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // Kept as an explicit no-op marker for the next PR: target-app method hooks are installed
        // here, after the app classloader is ready, and never for the system packages.
        runCatching {
            log(Log.DEBUG, TAG, "onPackageReady package=${param.packageName} first=${param.isFirstPackage}")
        }
    }

    private companion object {
        const val TAG = "FlowGlass/Api101"
    }
}
