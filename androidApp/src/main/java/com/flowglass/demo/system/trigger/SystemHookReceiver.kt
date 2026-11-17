package com.flowglass.demo.system.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.xposed.SystemHookProtocol

/**
 * Receives the protected explicit broadcast sent by the system-framework hook. The package name
 * is validated before it reaches the trigger service; the receiver itself has no side effects
 * when the user has disabled the system hook.
 */
class SystemHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SystemHookProtocol.ACTION_LOADED) {
            val hookCount = intent.getIntExtra(SystemHookProtocol.EXTRA_HOOKS, -1)
            SystemHookSettings.markLoaded(context, hookCount)
            // Reply immediately with the persisted switch value. The loaded flag in this reply
            // acknowledges the announcement and stops the hook from sending it again.
            SystemHookSettings.broadcastState(context)
            return
        }
        if (intent.action == SystemHookProtocol.ACTION_HEARTBEAT) {
            val hookCount = intent.getIntExtra(SystemHookProtocol.EXTRA_HOOKS, -1)
            SystemHookSettings.markHeartbeat(context, hookCount)
            TriggerService.start(context, TriggerService.ACTION_SYSTEM_HOOK_READY)
            return
        }
        if (intent.action != SystemHookProtocol.ACTION_FOREGROUND) return
        val packageName = intent.getStringExtra(SystemHookProtocol.EXTRA_PACKAGE) ?: return
        if (!PACKAGE_PATTERN.matches(packageName)) return
        val userId = intent.getIntExtra(SystemHookProtocol.EXTRA_USER, 0).coerceIn(0, 999_999)
        val started = TriggerService.start(context, TriggerService.ACTION_SYSTEM_FOREGROUND) {
            putExtra(SystemHookProtocol.EXTRA_PACKAGE, packageName)
            putExtra(SystemHookProtocol.EXTRA_USER, userId)
            putExtra(SystemHookProtocol.EXTRA_TIME, System.currentTimeMillis())
        }
        // A rejected background start is not fatal: the hook will deliver the next transition.
        if (!started) return
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    }
}
