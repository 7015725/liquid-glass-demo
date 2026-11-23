package com.flowglass.demo.system.trigger

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded on-disk queue for system-hook events whose foreground-service start was rejected.
 *
 * Android 12+ may refuse [TriggerService.start] while the app is in the background. Dropping the
 * event makes the rule silently miss a real transition, so keep the newest [MAX] events and replay
 * them the next time the service is allowed to start (app foreground, boot, alarm).
 */
internal object PendingSystemEvents {
    private const val PREFS = "pending_system_events"
    private const val KEY = "queue"
    internal const val MAX = 32

    fun enqueue(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        queue.put(encode(intent))
        while (queue.length() > MAX) queue.remove(0)
        prefs.edit().putString(KEY, queue.toString()).commit()
    }

    fun isNotEmpty(context: Context): Boolean {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        return runCatching { JSONArray(raw).length() > 0 }.getOrDefault(false)
    }

    /** Removes and returns the queued intents in arrival order. */
    fun drain(context: Context): List<Intent> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: return emptyList()
        val queue = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        prefs.edit().remove(KEY).commit()
        return (0 until queue.length()).mapNotNull { index ->
            runCatching { decode(queue.getJSONObject(index)) }.getOrNull()
        }
    }

    private fun encode(intent: Intent): JSONObject {
        val extras = JSONObject()
        intent.extras?.keySet()?.forEach { key ->
            when (val value = intent.extras?.get(key)) {
                is String -> extras.put(key, JSONObject().put("t", "s").put("v", value))
                is Int -> extras.put(key, JSONObject().put("t", "i").put("v", value))
                is Long -> extras.put(key, JSONObject().put("t", "l").put("v", value))
                is Boolean -> extras.put(key, JSONObject().put("t", "b").put("v", value))
            }
        }
        return JSONObject().put("action", intent.action ?: "").put("extras", extras)
    }

    private fun decode(json: JSONObject): Intent {
        val intent = Intent().setAction(json.getString("action"))
        val extras = json.getJSONObject("extras")
        for (key in extras.keys()) {
            val entry = extras.getJSONObject(key)
            when (entry.getString("t")) {
                "s" -> intent.putExtra(key, entry.getString("v"))
                "i" -> intent.putExtra(key, entry.getInt("v"))
                "l" -> intent.putExtra(key, entry.getLong("v"))
                "b" -> intent.putExtra(key, entry.getBoolean("v"))
            }
        }
        return intent
    }
}
