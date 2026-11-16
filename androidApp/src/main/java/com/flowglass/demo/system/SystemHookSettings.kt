package com.flowglass.demo.system

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.flowglass.demo.xposed.SystemHookProtocol

/**
 * User-controlled switch for the system-framework hook. The hook defaults to off and only
 * receives the current value through the protected state broadcast. This avoids reading app
 * private preferences from system_server and guarantees that "off" means the hook does nothing.
 */
object SystemHookSettings {
    private const val PREFS = "system_hook"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HEARTBEAT = "last_heartbeat"
    private const val HEARTBEAT_TTL_MS = 60_000L

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** True only after the system hook has proved it is alive recently. */
    fun isAlive(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val last = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_HEARTBEAT, 0L)
        return last > 0L && System.currentTimeMillis() - last in 0..HEARTBEAT_TTL_MS
    }

    fun markHeartbeat(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putLong(KEY_HEARTBEAT, System.currentTimeMillis()) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ENABLED, enabled) }
        broadcastState(context)
    }

    fun broadcastState(context: Context) {
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(SystemHookProtocol.ACTION_STATE)
                    .setPackage("android")
                    .putExtra(SystemHookProtocol.EXTRA_ENABLED, isEnabled(context))
            )
        }
    }
}
