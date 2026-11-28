package com.flowglass.demo.system.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.system.clipboard.ClipboardArchive
import com.flowglass.demo.system.clipboard.ClipboardCaptureSource
import com.flowglass.demo.system.clipboard.ClipboardPayload
import com.flowglass.demo.system.clipboard.ClipboardTriggerDispatch
import com.flowglass.demo.xposed.SystemHookProtocol

/**
 * Receives the protected explicit broadcast sent by the system-framework hook.
 *
 * P0 carries process start/end and app crash/ANR events through [SystemHookProtocol.ACTION_EVENT].
 * The old [SystemHookProtocol.ACTION_FOREGROUND] path remains accepted so an app update does not
 * break an older loaded hook.
 */
class SystemHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SystemHookProtocol.ACTION_LOADED) {
            val hookCount = intent.getIntExtra(SystemHookProtocol.EXTRA_HOOKS, -1)
            val families = intent.getIntExtra(
                SystemHookProtocol.EXTRA_AVAILABLE_FAMILIES,
                com.flowglass.demo.xposed.SystemHookFamily.defaultMask,
            )
            // Always answer LOADED so a restarted hook learns the current switch after the app
            // process survived. SystemHookSettings.send() still drops the identical reply if it
            // was already sent within two seconds, so repeated LOADED cannot flood.
            SystemHookSettings.markLoaded(context, hookCount, families)
            SystemHookSettings.broadcastState(context)
            return
        }
        if (intent.action == SystemHookProtocol.ACTION_HEARTBEAT) {
            val hookCount = intent.getIntExtra(SystemHookProtocol.EXTRA_HOOKS, -1)
            val families = intent.getIntExtra(
                SystemHookProtocol.EXTRA_AVAILABLE_FAMILIES,
                com.flowglass.demo.xposed.SystemHookFamily.defaultMask,
            )
            if (SystemHookSettings.markHeartbeat(context, hookCount, families)) {
                TriggerService.start(context, TriggerService.ACTION_SYSTEM_HOOK_READY)
            }
            return
        }
        if (intent.action == SystemHookProtocol.ACTION_EVENT) {
            receiveEvent(context, intent)
            return
        }
        // Compatibility with an older hook that only knew the foreground action.
        if (intent.action != SystemHookProtocol.ACTION_FOREGROUND) return
        receiveForeground(context, intent)
    }

    private fun receiveEvent(context: Context, intent: Intent) {
        val event = intent.getStringExtra(SystemHookProtocol.EXTRA_EVENT) ?: return
        if (event == SystemHookProtocol.EVENT_FOREGROUND) {
            receiveForeground(context, intent)
            return
        }
        if (event == SystemHookProtocol.EVENT_CLIPBOARD_CHANGED) {
            val text = intent.getStringExtra(SystemHookProtocol.EXTRA_CLIP_TEXT) ?: return
            if (text.isBlank()) return
            val payload = ClipboardPayload(
                content = text,
                originalLength = text.length,
                packageName = intent.getStringExtra(SystemHookProtocol.EXTRA_PACKAGE).orEmpty(),
                userId = intent.getIntExtra(SystemHookProtocol.EXTRA_USER, 0).coerceIn(0, 999_999),
                mimeType = intent.getStringExtra(SystemHookProtocol.EXTRA_CLIP_MIME).orEmpty()
                    .ifBlank { "text/plain" },
                label = intent.getStringExtra(SystemHookProtocol.EXTRA_CLIP_LABEL).orEmpty(),
                source = ClipboardCaptureSource.SYSTEM_HOOK,
                atEpochMs = intent.getLongExtra(
                    SystemHookProtocol.EXTRA_TIME,
                    System.currentTimeMillis(),
                ),
                truncated = false,
            )
            val pending = goAsync()
            ClipboardArchive.recordFromSystem(context, payload) { recorded ->
                pending.finish()
                if (recorded) ClipboardTriggerDispatch.deliver(context, payload)
            }
            return
        }
        if (event == SystemHookProtocol.EVENT_KEYGUARD_LOCKED ||
            event == SystemHookProtocol.EVENT_KEYGUARD_UNLOCKED
        ) {
            startOrQueue(context, TriggerService.ACTION_SYSTEM_EVENT) {
                putExtra(SystemHookProtocol.EXTRA_EVENT, event)
                putExtra(SystemHookProtocol.EXTRA_TIME, System.currentTimeMillis())
            }
            return
        }
        if (event !in SUPPORTED_EVENTS) return
        val packageName = intent.getStringExtra(SystemHookProtocol.EXTRA_PACKAGE) ?: return
        if (!PACKAGE_PATTERN.matches(packageName)) return
        val userId = intent.getIntExtra(SystemHookProtocol.EXTRA_USER, -1)
        if (userId !in 0..999_999) return
        val now = System.currentTimeMillis()
        SystemEventCache.record(event, packageName, userId, now)
        startOrQueue(context, TriggerService.ACTION_SYSTEM_EVENT) {
            putExtra(SystemHookProtocol.EXTRA_EVENT, event)
            putExtra(SystemHookProtocol.EXTRA_PACKAGE, packageName)
            putExtra(SystemHookProtocol.EXTRA_USER, userId)
            putExtra(SystemHookProtocol.EXTRA_TIME, now)
        }
    }

    private fun receiveForeground(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(SystemHookProtocol.EXTRA_PACKAGE) ?: return
        if (!PACKAGE_PATTERN.matches(packageName)) return
        val userId = intent.getIntExtra(SystemHookProtocol.EXTRA_USER, 0)
        if (userId !in 0..999_999) return
        startOrQueue(context, TriggerService.ACTION_SYSTEM_FOREGROUND) {
            putExtra(SystemHookProtocol.EXTRA_PACKAGE, packageName)
            putExtra(SystemHookProtocol.EXTRA_USER, userId)
            putExtra(SystemHookProtocol.EXTRA_TIME, System.currentTimeMillis())
        }
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
        private val SUPPORTED_EVENTS = setOf(
            SystemHookProtocol.EVENT_PROCESS_STARTED,
            SystemHookProtocol.EVENT_PROCESS_ENDED,
            SystemHookProtocol.EVENT_APP_CRASH,
            SystemHookProtocol.EVENT_APP_ANR,
            SystemHookProtocol.EVENT_MEDIA_PLAYING,
            SystemHookProtocol.EVENT_MEDIA_PAUSED,
            SystemHookProtocol.EVENT_MEDIA_STOPPED,
            SystemHookProtocol.EVENT_FOREGROUND_SERVICE_STARTED,
            SystemHookProtocol.EVENT_FOREGROUND_SERVICE_STOPPED,
            SystemHookProtocol.EVENT_CLIPBOARD_CHANGED,
        )
    }
    /**
     * Android 12+ can reject a background FGS start. Persist the event instead of dropping it;
     * [TriggerService.ensureStarted] replays the bounded queue on the next allowed start.
     */
    internal fun startOrQueue(
        context: Context,
        action: String,
        start: (android.content.Intent) -> Boolean = { TriggerService.startIntent(context, it) },
        configure: android.content.Intent.() -> Unit,
    ) {
        val intent = TriggerService.intent(context, action, configure = configure)
        if (!start(intent)) PendingSystemEvents.enqueue(context, intent)
    }

}
