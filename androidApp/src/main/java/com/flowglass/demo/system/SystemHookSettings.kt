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
    private const val KEY_HOOKS = "hook_count"
    private const val HEARTBEAT_TTL_MS = 60_000L

    enum class Status {
        DISABLED,
        WAITING,
        NO_HOOKS,
        READY,
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /**
     * READY requires both a fresh heartbeat and at least one installed detection point. A loaded
     * hook with zero methods must not be presented as usable.
     */
    fun status(context: Context): Status {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return status(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            heartbeatFresh = heartbeatFresh(prefs.getLong(KEY_HEARTBEAT, 0L)),
            hookCount = prefs.getInt(KEY_HOOKS, -1),
        )
    }

    internal fun status(enabled: Boolean, heartbeatFresh: Boolean, hookCount: Int): Status = when {
        !enabled -> Status.DISABLED
        !heartbeatFresh -> Status.WAITING
        hookCount <= 0 -> Status.NO_HOOKS
        else -> Status.READY
    }

    fun isAlive(context: Context): Boolean = status(context) == Status.READY

    fun markHeartbeat(context: Context, hookCount: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putLong(KEY_HEARTBEAT, System.currentTimeMillis())
                putInt(KEY_HOOKS, hookCount)
            }
    }

    private fun heartbeatFresh(last: Long): Boolean =
        last > 0L && System.currentTimeMillis() - last in 0..HEARTBEAT_TTL_MS

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
