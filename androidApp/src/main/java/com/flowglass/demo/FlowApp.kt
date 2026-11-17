package com.flowglass.demo

import android.app.Application
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.RunningNotification
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.system.trigger.TriggerService
import com.flowglass.demo.ui.primeComposeStateOnMainThread
import com.flowglass.demo.xposed.ModernModuleStatus
import com.flowglass.demo.xposed.method.MethodHookConfigPublisher

/**
 * Installs the root-grant cache for every entry point, not just the Activity: a background
 * service or a boot receiver can execute a rule before any screen exists.
 */
class FlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Object-level Compose state must be initialized on main before any service or
        // broadcast receiver can touch it from a worker thread.
        primeComposeStateOnMainThread()
        Root.install(this)
        // API101 no longer injects the module into its own app. Bind the framework service and
        // mark activation from that; the classic entry still marks itself through XposedEntry.
        ModernModuleStatus.install {
            MethodHookConfigPublisher.publish(this)
        }
        MethodHookConfigPublisher.publish(this)
        // Upgrade path: old builds created keepalive/triggers channels; only "running" remains.
        RunningNotification.cleanupLegacyChannels(this)
        // Re-send the persisted hook state after process/system restarts; the system hook starts
        // disabled when its own process is recreated.
        SystemHookSettings.broadcastState(this)
        // A reboot, an alarm or a plain launch all end here; the service itself decides whether
        // the engine switch and the enabled-rule set still justify listening.
        TriggerService.ensureStarted(this)
    }
}
