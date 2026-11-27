package com.flowglass.demo.system.trigger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

internal data class NotificationInfo(
    val app: String,
    val title: String,
    val text: String,
    val subtext: String,
    val category: String,
    val channel: String,
    val ongoing: Boolean,
    val userId: Int,
    val id: Int,
    val tag: String,
    val postedAt: Long,
    val removed: Boolean,
)

/** Process-wide fan-out from the system notification listener to the trigger host. */
internal object NotificationEvents {
    typealias Listener = (NotificationInfo) -> Unit
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun add(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    fun dispatch(info: NotificationInfo) {
        listeners.forEach { listener -> runCatching { listener(info) } }
    }
}

/**
 * Requires notification access in system settings. It forwards only display fields and state
 * flags; the full payload, RemoteViews and PendingIntents remain inside the system service.
 */
class FlowNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val info = snapshot(sbn, removed = false) ?: return
        NotificationEvents.dispatch(info)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        val info = snapshot(sbn, removed = true) ?: return
        NotificationEvents.dispatch(info)
    }

    private fun snapshot(sbn: StatusBarNotification?, removed: Boolean): NotificationInfo? {
        val packageName = sbn?.packageName ?: return null
        // The foreground service updates its own notification whenever the listening count
        // changes; feeding that back into a notification rule would loop forever.
        if (packageName == this.packageName) return null
        val notification = sbn.notification
        val extras = notification?.extras
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val rawText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val text = if (rawText.isEmpty()) bigText else rawText
        return NotificationInfo(
            app = packageName,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = text,
            subtext = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            category = notification?.category.orEmpty(),
            channel = notification?.channelId.orEmpty(),
            ongoing = notification?.flags?.and(Notification.FLAG_ONGOING_EVENT) != 0,
            userId = sbn.user?.hashCode() ?: 0,
            id = sbn.id,
            tag = sbn.tag.orEmpty(),
            postedAt = sbn.postTime,
            removed = removed,
        )
    }
}
