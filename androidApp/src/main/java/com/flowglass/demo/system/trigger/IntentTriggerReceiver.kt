package com.flowglass.demo.system.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource

/**
 * Explicit external entry point. A caller must target this component and carry the rule's token;
 * there is no implicit action filter, so unrelated broadcasts never reach the trigger engine.
 */
class IntentTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_TOKEN_ALT)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        val event = TriggerEvent(
            kind = TriggerKinds.INTENT_RECEIVED,
            source = TriggerSource.INTENT,
            atEpochMs = System.currentTimeMillis(),
            data = mapOf(
                "intent.token" to token,
                "intent.action" to intent.action.orEmpty(),
                "intent.data" to intent.dataString.orEmpty(),
                "intent.extras" to serializeExtras(intent),
            ),
        )
        if (TriggerService.deliverIfRunning(event)) return
        TriggerService.start(context, TriggerService.ACTION_INTENT) {
            putExtra(EXTRA_INTENT_TOKEN, token)
            putExtra(EXTRA_INTENT_ACTION, intent.action.orEmpty())
            putExtra(EXTRA_INTENT_DATA, intent.dataString.orEmpty())
            putExtra(EXTRA_INTENT_EXTRAS, event.data.getValue("intent.extras"))
        }
    }

    private fun serializeExtras(intent: Intent): String {
        val bundle = intent.extras ?: return ""
        val out = StringBuilder()
        var count = 0
        for (key in bundle.keySet()) {
            if (key == EXTRA_TOKEN || key == EXTRA_TOKEN_ALT) continue
            if (count >= MAX_EXTRAS || out.length >= MAX_EXTRAS_CHARS) break
            val value = when (val raw = bundle.get(key)) {
                is String, is Int, is Long, is Boolean, is Float, is Double -> raw.toString()
                else -> continue
            }
            val line = "$key=$value"
            if (out.isNotEmpty()) out.append('\n')
            out.append(line.take(MAX_VALUE_CHARS))
            count++
        }
        return out.toString()
    }

    companion object {
        const val EXTRA_TOKEN = "flowglass_token"
        const val EXTRA_TOKEN_ALT = "token"
        const val EXTRA_INTENT_TOKEN = "intent_token"
        const val EXTRA_INTENT_ACTION = "intent_action"
        const val EXTRA_INTENT_DATA = "intent_data"
        const val EXTRA_INTENT_EXTRAS = "intent_extras"
        private const val MAX_EXTRAS = 32
        private const val MAX_EXTRAS_CHARS = 4_096
        private const val MAX_VALUE_CHARS = 512
    }
}
