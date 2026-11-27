package com.flowglass.demo.system.trigger

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.flowglass.demo.system.Perms
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource

/**
 * Reads metadata changes from active media sessions. Notification access is required because
 * MediaSessionManager only exposes other apps' sessions to a notification listener.
 */
internal class MediaTrackSource(
    private val context: Context,
    private val emit: (TriggerEvent) -> Unit,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, FlowNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<MediaSession.Token, ControllerCallback>()
    private var active = false
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        update(controllers)
    }

    fun start() {
        if (active || manager == null) return
        if (!Perms.isNotificationListenerEnabled(context)) {
            Log.w(TAG, "notification listener not enabled; media track trigger inactive")
            return
        }
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
            active = true
            update(manager.getActiveSessions(component))
        }.onFailure {
            Log.w(TAG, "cannot observe media sessions: ${it.message}")
            stop()
        }
    }

    fun stop() {
        if (active) runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        callbacks.values.forEach { it.close() }
        callbacks.clear()
        active = false
    }

    private fun update(controllers: List<MediaController>?) {
        val next = controllers.orEmpty().associateBy { it.sessionToken }
        for ((token, callback) in callbacks.toList()) {
            if (token !in next) {
                callback.close()
                callbacks.remove(token)
            }
        }
        for ((token, controller) in next) {
            if (token in callbacks) continue
            val callback = ControllerCallback(controller) { metadata -> emitTrack(controller, metadata) }
            callbacks[token] = callback
            runCatching { controller.registerCallback(callback, handler) }
            // Seed the key without firing: enabling a rule is not itself a track change.
            callback.lastKey = metadataKey(controller.metadata)
        }
    }

    private fun emitTrack(controller: MediaController, metadata: MediaMetadata?) {
        val key = metadataKey(metadata)
        val callback = callbacks[controller.sessionToken] ?: return
        if (key == callback.lastKey) return
        callback.lastKey = key
        emit(
            TriggerEvent(
                kind = TriggerKinds.MEDIA_TRACK,
                source = TriggerSource.MEDIA,
                atEpochMs = System.currentTimeMillis(),
                data = mapOf(
                    "media.title" to (metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""),
                    "media.artist" to (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""),
                    "media.album" to (metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""),
                    "media.package" to controller.packageName.orEmpty(),
                ),
            ),
        )
    }

    private fun metadataKey(metadata: MediaMetadata?): String {
        if (metadata == null) return ""
        return listOf(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
        ).joinToString("\u0000")
    }

    private class ControllerCallback(
        private val controller: MediaController,
        private val onMetadata: (MediaMetadata?) -> Unit,
    ) : MediaController.Callback() {
        @Volatile var lastKey: String = ""
        override fun onMetadataChanged(metadata: MediaMetadata?) = onMetadata(metadata)

        fun close() {
            runCatching { controller.unregisterCallback(this) }
        }
    }

    private companion object {
        const val TAG = "FlowMediaTrigger"
    }
}
