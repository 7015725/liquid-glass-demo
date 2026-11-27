package com.flowglass.demo.system.trigger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource

/** Transparent ACTION_SEND target; it only forwards the shared fields and closes at once. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        @Suppress("DEPRECATION")
        val uri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString().orEmpty()
        val mime = intent?.type.orEmpty()
        if (text.isBlank() && uri.isBlank()) {
            finish()
            return
        }
        val event = TriggerEvent(
            kind = TriggerKinds.SHARE_RECEIVED,
            source = TriggerSource.SHARE,
            atEpochMs = System.currentTimeMillis(),
            data = mapOf(
                "share.text" to text,
                "share.uri" to uri,
                "share.mime" to mime,
            ),
        )
        if (!TriggerService.deliverIfRunning(event)) {
            TriggerService.start(this, TriggerService.ACTION_SHARE) {
                putExtra(EXTRA_SHARE_TEXT, text)
                putExtra(EXTRA_SHARE_URI, uri)
                putExtra(EXTRA_SHARE_MIME, mime)
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_SHARE_TEXT = "share_text"
        const val EXTRA_SHARE_URI = "share_uri"
        const val EXTRA_SHARE_MIME = "share_mime"
    }
}
