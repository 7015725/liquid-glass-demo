package com.flowglass.demo.system

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.flowglass.demo.xposed.SystemHookProtocol

/**
 * User-controlled switch and handshake state for the system-framework hook.
 *
 * The hook defaults to off. It announces that it is loaded with a protected broadcast; the app
 * replies with its persisted switch value and an [SystemHookProtocol.EXTRA_APP_KNOWS_LOADED]
 * flag. That flag lets the hook distinguish a normal app update from an acknowledgement of the
 * loaded announcement, so the two broadcasts cannot form a loop.
 */
object SystemHookSettings {
    private const val PREFS = "system_hook"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LOADED = "hook_loaded"
    private const val KEY_HEARTBEAT = "last_heartbeat"
    private const val KEY_HOOKS = "hook_count"
    private const val HEARTBEAT_TTL_MS = 60_000L

    enum class Status {
        /** No ACTION_LOADED has ever reached this app process. */
        NOT_LOADED,
        /** The hook is installed, but the user's switch is off. */
        LOADED_DISABLED,
        /** The switch is on, but no recent heartbeat has proved the hook is working. */
        LOADED_WAITING,
        /** A fresh heartbeat arrived but none of the expected detection points was installed. */
        NO_HOOKS,
        /** Loaded, enabled, heartbeat fresh, and at least one detection point installed. */
        READY,
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** Whether the hook has announced itself to this app process (or sent a heartbeat). */
    fun isLoaded(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOADED, false)

    fun status(context: Context): Status {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return status(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            loaded = prefs.getBoolean(KEY_LOADED, false),
            heartbeatFresh = heartbeatFresh(prefs.getLong(KEY_HEARTBEAT, 0L)),
            hookCount = prefs.getInt(KEY_HOOKS, -1),
        )
    }

    internal fun status(
        enabled: Boolean,
        loaded: Boolean,
        heartbeatFresh: Boolean,
        hookCount: Int,
    ): Status = when {
        !loaded -> Status.NOT_LOADED
        !enabled -> Status.LOADED_DISABLED
        !heartbeatFresh -> Status.LOADED_WAITING
        hookCount <= 0 -> Status.NO_HOOKS
        else -> Status.READY
    }

    fun isAlive(context: Context): Boolean = status(context) == Status.READY

    /** Record the hook's protected "I am loaded" announcement. */
    fun markLoaded(context: Context, hookCount: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_LOADED, true)
                putInt(KEY_HOOKS, hookCount)
            }
    }

    fun markHeartbeat(context: Context, hookCount: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_LOADED, true)
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

    /**
     * Send the current switch state to the system hook. The loaded flag is part of the reply so a
     * hook that just announced itself does not announce again in response to this state.
     */
    fun broadcastState(context: Context) {
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(SystemHookProtocol.ACTION_STATE)
                    .setPackage("android")
                    .putExtra(SystemHookProtocol.EXTRA_ENABLED, isEnabled(context))
                    .putExtra(SystemHookProtocol.EXTRA_APP_KNOWS_LOADED, isLoaded(context))
            )
        }
    }
}
