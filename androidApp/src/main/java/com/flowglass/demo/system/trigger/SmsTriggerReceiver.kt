package com.flowglass.demo.system.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource

/** System SMS broadcast bridge. The manifest receiver is protected by BROADCAST_SMS. */
class SmsTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val event = TriggerEvent(
            kind = TriggerKinds.SMS_RECEIVED,
            source = TriggerSource.SMS,
            atEpochMs = System.currentTimeMillis(),
            data = mapOf(
                "sms.sender" to sender,
                "sms.body" to body,
                "sms.timestamp" to timestamp.toString(),
            ),
        )
        if (TriggerService.deliverIfRunning(event)) return
        TriggerService.start(context, TriggerService.ACTION_SMS) {
            putExtra(EXTRA_SMS_SENDER, sender)
            putExtra(EXTRA_SMS_BODY, body)
            putExtra(EXTRA_SMS_TIMESTAMP, timestamp)
        }
    }

    companion object {
        const val EXTRA_SMS_SENDER = "sms_sender"
        const val EXTRA_SMS_BODY = "sms_body"
        const val EXTRA_SMS_TIMESTAMP = "sms_timestamp"
    }
}
