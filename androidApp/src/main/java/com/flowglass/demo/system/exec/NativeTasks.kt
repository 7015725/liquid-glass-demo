package com.flowglass.demo.system.exec

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object NativeTasks {
    suspend fun run(context: Context, cmd: Cmd, task: CommandItem): TaskOutcome = when (task.name) {
        "复制到剪贴板" -> withContext(Dispatchers.Main.immediate) {
            val manager = checkNotNull(context.getSystemService(ClipboardManager::class.java)) { "剪贴板服务不可用" }
            manager.setPrimaryClip(ClipData.newPlainText(cmd.title, task.parameter))
            TaskOutcome(StepStatus.SUCCESS, "已复制到剪贴板")
        }
        "延时等待" -> {
            val millis = TaskParameters.delayMillis(task.parameter)
            kotlinx.coroutines.delay(millis)
            TaskOutcome(StepStatus.SUCCESS, "已等待 $millis 毫秒")
        }
        "调节音量" -> {
            val percent = TaskParameters.percent(task.parameter)
            val audio = checkNotNull(context.getSystemService(android.media.AudioManager::class.java)) { "音频服务不可用" }
            val stream = android.media.AudioManager.STREAM_MUSIC
            val value = java.lang.Math.round(audio.getStreamMaxVolume(stream) * percent / 100).toInt()
            audio.setStreamVolume(stream, value, 0)
            TaskOutcome(StepStatus.SUCCESS, "媒体音量已设为 $percent%")
        }
        "发送通知" -> {
            val missing = android.os.Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            val manager = checkNotNull(context.getSystemService(android.app.NotificationManager::class.java)) { "通知服务不可用" }
            if (missing || !manager.areNotificationsEnabled()) TaskOutcome(StepStatus.FAILURE, "缺少通知权限")
            else {
                val channel = "command_notifications"
                manager.createNotificationChannel(android.app.NotificationChannel(channel, "指令通知", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                val split = task.parameter.indexOf('|')
                val title = if (split < 0) cmd.title else task.parameter.substring(0, split).ifBlank { cmd.title }
                val body = if (split < 0) task.parameter else task.parameter.substring(split + 1)
                val notification = android.app.Notification.Builder(context, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(body).setAutoCancel(true).build()
                manager.notify("command:${cmd.id}:${task.id}", 0, notification)
                TaskOutcome(StepStatus.SUCCESS, "已发送通知")
            }
        }
        "开关勿扰" -> {
            val mode = TaskParameters.mode(task.parameter)
            val manager = checkNotNull(context.getSystemService(android.app.NotificationManager::class.java)) { "通知服务不可用" }
            if (!manager.isNotificationPolicyAccessGranted) TaskOutcome(StepStatus.FAILURE, "缺少勿扰权限")
            else {
                val filter = manager.currentInterruptionFilter
                check(mode != SwitchMode.TOGGLE || filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN) { "无法读取勿扰状态" }
                val enabled = when (mode) {
                    SwitchMode.ON -> true
                    SwitchMode.OFF -> false
                    SwitchMode.TOGGLE -> filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                }
                manager.setInterruptionFilter(if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_NONE else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                TaskOutcome(StepStatus.SUCCESS, if (enabled) "勿扰已开启" else "勿扰已关闭")
            }
        }
        "调节亮度" -> withContext(Dispatchers.IO) {
            val percent = TaskParameters.percent(task.parameter)
            if (!android.provider.Settings.System.canWrite(context)) TaskOutcome(StepStatus.FAILURE, "缺少修改系统设置权限")
            else {
                val resolver = context.contentResolver
                check(android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)) { "无法切换手动亮度模式" }
                val value = java.lang.Math.round(255 * percent / 100).toInt()
                check(android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, value)) { "亮度写入失败" }
                TaskOutcome(StepStatus.SUCCESS, "亮度已设为 $percent%")
            }
        }
        "启动应用" -> {
            val requested = task.parameter.trim()
            require(requested.isNotEmpty()) { "参数格式不正确：期望 应用包名或应用名称" }
            val intent = withContext(Dispatchers.IO) {
                context.packageManager.getLaunchIntentForPackage(requested) ?: run {
                    val apps = LaunchableApps.load(context)
                    val app = apps.firstOrNull { it.label.equals(requested, ignoreCase = true) }
                        ?: apps.firstOrNull { it.label.contains(requested, ignoreCase = true) }
                    app?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) }
                }
            }
            if (intent == null) TaskOutcome(StepStatus.FAILURE, "未找到可启动应用：$requested")
            else {
                withContext(Dispatchers.Main.immediate) {
                    context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                TaskOutcome(StepStatus.SUCCESS, "已启动应用：$requested")
            }
        }
        "HTTP 请求" -> HttpTask.run(TaskParameters.http(task.parameter))
        "执行 Shell(Root)" -> {
            require(task.parameter.isNotBlank()) { "参数格式不正确：期望 非空 Shell 命令" }
            val root = com.flowglass.demo.system.Root.check(request = true)
            if (!root.granted) TaskOutcome(StepStatus.FAILURE, "缺少 Root 权限")
            else {
                val result = com.flowglass.demo.system.Root.runProcess(listOf(root.suPath, "-c", task.parameter))
                when {
                    result.timedOut -> TaskOutcome(StepStatus.FAILURE, "Shell 执行超时（30 秒）\n${result.output}")
                    result.exitCode != 0 -> TaskOutcome(StepStatus.FAILURE, "Shell 退出码 ${result.exitCode}\n${result.output}")
                    else -> TaskOutcome(StepStatus.SUCCESS, result.output.ifEmpty { "Shell 执行完成" })
                }
            }
        }
        "开关 WiFi", "开关蓝牙" -> RadioTask.run(context, task.name, task.parameter)
        else -> TaskOutcome(StepStatus.SKIPPED, "暂不支持该任务")
    }
}
