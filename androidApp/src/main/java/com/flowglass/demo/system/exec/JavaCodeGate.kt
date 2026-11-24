package com.flowglass.demo.system.exec

import android.content.Context
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.allItems
import java.security.MessageDigest

/**
 * Per-device trust gate for imported Java code. A hash present in [KEY_PENDING] was imported and
 * never confirmed here; absent hashes are code the user wrote, edited or explicitly enabled.
 *
 * Hashing the source (not the step id) survives import id re-allocation, moves and copies. Editing
 * the code produces a new hash, which counts as authoring and therefore as consent.
 */
internal object JavaCodeGate {
    private const val PREFS = "java_code_gate"
    private const val KEY_PENDING = "pending_codes"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPending(context: Context, code: String): Boolean =
        prefs(context).getStringSet(KEY_PENDING, emptySet()).orEmpty().contains(hash(code))

    fun markPending(context: Context, code: String) {
        val next = prefs(context).getStringSet(KEY_PENDING, emptySet()).orEmpty().toMutableSet()
        if (next.add(hash(code))) prefs(context).edit().putStringSet(KEY_PENDING, next).apply()
    }

    fun confirm(context: Context, code: String) {
        val next = prefs(context).getStringSet(KEY_PENDING, emptySet()).orEmpty().toMutableSet()
        if (next.remove(hash(code))) prefs(context).edit().putStringSet(KEY_PENDING, next).apply()
    }

    fun markImported(context: Context, commands: List<Cmd>) {
        commands.forEach { command ->
            command.actions.allItems()
                .filter { it.typeId == "action.java_code" }
                .forEach { markPending(context, JavaCodeParameters.code(it.parameter).trim()) }
        }
    }

    private fun hash(code: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
