package com.flowglass.demo.xposed

import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * App-side half of the API101 activation check. Modern frameworks do not inject modules into the
 * module's own app, so the old self-hook marker never becomes true there. The framework service is
 * the supported replacement; it also gives us the real framework name/version and current scope.
 *
 * The listener is registered once per app process. A framework restart or a dead binder clears the
 * marker, and a later bind marks it again. No data from the hooked target app is involved here.
 */
internal object ModernModuleStatus {
    private const val TAG = "FlowGlass/ModuleStatus"
    private var installed = false
    @Volatile private var current: XposedService? = null
    @Volatile private var onBound: (() -> Unit)? = null

    fun service(): XposedService? = current

    fun install(onServiceBound: (() -> Unit)? = null) {
        onBound = onServiceBound
        if (installed) return
        installed = true
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    current = service
                    runCatching {
                        ModuleStatus.markModern(
                            apiVersion = service.apiVersion,
                            frameworkName = service.frameworkName,
                            frameworkVersion = service.frameworkVersion,
                            frameworkVersionCode = service.frameworkVersionCode,
                            scope = service.scope,
                        )
                    }.onFailure { failure ->
                        runCatching { Log.w(TAG, "service metadata unavailable: ${failure.javaClass.simpleName}") }
                    }
                    runCatching { onBound?.invoke() }
                }

                override fun onServiceDied(service: XposedService) {
                    current = null
                    ModuleStatus.clearModern()
                }
            })
        }.onFailure { failure ->
            runCatching { Log.w(TAG, "service registration failed: ${failure.javaClass.simpleName}") }
        }
    }
}
