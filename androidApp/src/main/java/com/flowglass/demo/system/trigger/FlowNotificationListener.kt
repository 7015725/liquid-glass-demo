package com.flowglass.demo.system.trigger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide fan-out from the system notification listener to the trigger host. */
internal object NotificationEvents {
    private val listeners = CopyOnWriteArrayList<(app: String, title: String, text: String) -> Unit>()

    fun add(listener: (app: String, title: String, text: String) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: (app: String, title: String, text: String) -> Unit) {
        listeners.remove(listener)
    }

    fun dispatch(app: String, title: String, text: String) {
        listeners.forEach { listener -> runCatching { listener(app, title, text) } }
    }
}

/**
 * Requires the user to grant notification access in system settings. The service is declared in
 * the manifest with `BIND_NOTIFICATION_LISTENER_SERVICE`; it only forwards the three fields the
 * trigger matches on, never the full notification payload.
 */
class FlowNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        // The foreground service updates its own notification whenever the listening count
        // changes; feeding that back into a "收到通知" rule would loop forever.
        if (packageName == this.packageName) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        NotificationEvents.dispatch(packageName, title, text)
    }
}
