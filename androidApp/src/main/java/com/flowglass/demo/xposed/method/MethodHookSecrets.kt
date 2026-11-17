package com.flowglass.demo.xposed.method

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Per-hook bearer tokens. The token is written only to app-private preferences and to the
 * framework's remote configuration for the selected target app; it is never put in commands.json,
 * backups, logs, broadcasts or exported files.
 */
internal class MethodHookSecrets(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(hookId: String): String? = prefs.getString(key(hookId), null)

    fun ensureToken(hookId: String): String {
        token(hookId)?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val value = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(key(hookId), value).apply()
        return value
    }

    fun remove(hookId: String) {
        prefs.edit().remove(key(hookId)).apply()
    }

    private fun key(hookId: String): String = "hook.$hookId"
    private companion object {
        const val PREFS = "method_hook_secrets"
    }
}
