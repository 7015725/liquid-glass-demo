package com.flowglass.demo.system.exec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Notification channel shared by code actions and system prompts. */
internal object CommandNotifications {
    fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("缺少通知权限")
        }
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("通知服务不可用")
        val channel = "command_notifications"
        manager.createNotificationChannel(
            NotificationChannel(channel, "指令通知", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify("script:${System.nanoTime()}", 0, notification)
    }
}
