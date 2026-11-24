package com.flowglass.demo.system.exec

import android.content.Context

/**
 * Survives a process death: written before a run, cleared after it. A leftover marker means the
 * last Java Code run took the app down, so the next launch can say so once.
 */
internal object JavaCodeCrashMarker {
    private const val PREFS = "java_code_run"
    private const val KEY_RUNNING = "running_since"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markRunning(context: Context) {
        prefs(context).edit().putLong(KEY_RUNNING, System.currentTimeMillis()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RUNNING).apply()
    }

    fun consumeCrash(context: Context): Boolean {
        if (prefs(context).getLong(KEY_RUNNING, 0L) == 0L) return false
        clear(context)
        return true
    }
}
