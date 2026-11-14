package com.flowglass.demo.system.exec

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.widget.Toast
import com.flowglass.demo.system.FlowAccessibilityService
import com.flowglass.demo.system.Root
import com.flowglass.engine.BuiltInActions
import com.flowglass.engine.ComponentMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

internal object SystemActions {
    private fun meta(typeId: String): ComponentMeta =
        BuiltInActions.metadata.first { it.typeId == typeId }

    suspend fun vibrate(context: Context, parameter: String): TaskOutcome {
        val millis = TaskParameters.durationMillis(parameter).coerceIn(1L, 600_000L)
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return TaskOutcome(StepStatus.FAILURE, "设备不支持振动")
        return try {
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            TaskOutcome(StepStatus.SUCCESS, "已振动 $millis 毫秒")
        } catch (_: Throwable) {
            TaskOutcome(StepStatus.FAILURE, "振动失败")
        }
    }

    suspend fun flashlight(context: Context, parameter: String): TaskOutcome {
        val mode = TaskParameters.mode(parameter)
        val enabled = when (mode) {
            SwitchMode.ON -> true
            SwitchMode.OFF -> false
            SwitchMode.TOGGLE -> !FlashlightController.isOn()
        }
        val error = FlashlightController.set(context, enabled)
        return if (error == null) {
            TaskOutcome(StepStatus.SUCCESS, if (enabled) "手电筒已开启" else "手电筒已关闭")
        } else {
            TaskOutcome(StepStatus.FAILURE, error)
        }
    }

    suspend fun ringerMode(context: Context, parameter: String): TaskOutcome {
        val mode = TaskParameters.token(meta("action.ringer_mode"), parameter, "mode")
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return TaskOutcome(StepStatus.FAILURE, "音频服务不可用")
        if (mode == "silent") {
            val notifications = context.getSystemService(android.app.NotificationManager::class.java)
            if (notifications?.isNotificationPolicyAccessGranted != true) {
                return TaskOutcome(StepStatus.FAILURE, "缺少勿扰权限")
            }
        }
        return try {
            audio.ringerMode = when (mode) {
                "ring" -> AudioManager.RINGER_MODE_NORMAL
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "silent" -> AudioManager.RINGER_MODE_SILENT
                else -> return TaskOutcome(StepStatus.FAILURE, "响铃模式参数无效")
            }
            val label = when (mode) {
                "ring" -> "响铃"
                "vibrate" -> "振动"
                else -> "静音"
            }
            TaskOutcome(StepStatus.SUCCESS, "已切换为$label")
        } catch (_: SecurityException) {
            TaskOutcome(StepStatus.FAILURE, "缺少修改声音模式的权限")
        }
    }

    suspend fun mediaControl(context: Context, parameter: String): TaskOutcome {
        val action = TaskParameters.token(meta("action.media_control"), parameter, "action")
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return TaskOutcome(StepStatus.FAILURE, "音频服务不可用")
        val (code, label) = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY to "播放"
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "暂停"
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "上一首"
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT to "下一首"
            else -> return TaskOutcome(StepStatus.FAILURE, "媒体操作参数无效")
        }
        return try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            TaskOutcome(StepStatus.SUCCESS, "已发送媒体键：$label")
        } catch (_: Throwable) {
            TaskOutcome(StepStatus.FAILURE, "媒体键发送失败")
        }
    }

    suspend fun openUrl(context: Context, parameter: String): TaskOutcome {
        val url = parameter.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return TaskOutcome(StepStatus.FAILURE, "参数格式不正确：期望 http:// 或 https:// 开头的网址")
        }
        return try {
            withContext(Dispatchers.Main.immediate) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            TaskOutcome(StepStatus.SUCCESS, "已打开网页")
        } catch (_: ActivityNotFoundException) {
            TaskOutcome(StepStatus.FAILURE, "没有可打开网页的应用")
        } catch (_: Throwable) {
            TaskOutcome(StepStatus.FAILURE, "打开网页失败")
        }
    }

    suspend fun speak(context: Context, parameter: String): TaskOutcome {
        val text = parameter.trim()
        if (text.isEmpty()) return TaskOutcome(StepStatus.FAILURE, "朗读内容为空")
        var engine: TextToSpeech? = null
        try {
            val initialized = CompletableDeferred<Boolean>()
            engine = withContext(Dispatchers.Main.immediate) {
                TextToSpeech(context) { status -> initialized.complete(status == TextToSpeech.SUCCESS) }
            }
            val ready = withTimeoutOrNull(10_000) { initialized.await() } ?: false
            if (!ready) return TaskOutcome(StepStatus.FAILURE, "系统 TTS 不可用")
            val tts = engine
            val finished = CompletableDeferred<String>()
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    finished.complete("done")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    finished.complete("error")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    finished.complete("error")
                }
            })
            val utteranceId = "flowglass-${System.nanoTime()}"
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.SUCCESS) {
                return TaskOutcome(StepStatus.FAILURE, "朗读启动失败")
            }
            val result = withTimeoutOrNull(30_000) { finished.await() }
            return when (result) {
                "done" -> TaskOutcome(StepStatus.SUCCESS, "朗读完成")
                "error" -> TaskOutcome(StepStatus.FAILURE, "朗读失败")
                else -> TaskOutcome(StepStatus.SUCCESS, "朗读超过 30 秒，已继续下一步")
            }
        } catch (_: Throwable) {
            return TaskOutcome(StepStatus.FAILURE, "系统 TTS 不可用")
        } finally {
            withContext(NonCancellable) {
                try {
                    engine?.stop()
                    engine?.shutdown()
                } catch (_: Throwable) {
                }
            }
        }
    }

    suspend fun toast(context: Context, parameter: String): TaskOutcome {
        val text = parameter.trim()
        if (text.isEmpty()) return TaskOutcome(StepStatus.FAILURE, "提示内容为空")
        return try {
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
            TaskOutcome(StepStatus.SUCCESS, "已显示提示")
        } catch (_: Throwable) {
            TaskOutcome(StepStatus.FAILURE, "显示提示失败")
        }
    }

    suspend fun screenTimeout(context: Context, parameter: String): TaskOutcome {
        if (!Settings.System.canWrite(context)) {
            return TaskOutcome(StepStatus.FAILURE, "缺少修改系统设置权限")
        }
        val millis = TaskParameters.durationMillis(parameter).coerceIn(5_000L, 1_800_000L)
        return try {
            val ok = Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, millis.toInt(),
            )
            if (ok) TaskOutcome(StepStatus.SUCCESS, "屏幕超时已设为 ${millis / 1000} 秒")
            else TaskOutcome(StepStatus.FAILURE, "屏幕超时写入失败")
        } catch (_: Throwable) {
            TaskOutcome(StepStatus.FAILURE, "屏幕超时写入失败")
        }
    }

    suspend fun autoRotate(context: Context, parameter: String): TaskOutcome {
        if (!Settings.System.canWrite(context)) {
            return TaskOutcome(StepStatus.FAILURE, "缺少修改系统设置权限")
        }
        val mode = TaskParameters.mode(parameter)
        return try {
            val current = Settings.System.getInt(
                context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0,
            ) == 1
            val enabled = when (mode) {
                SwitchMode.ON -> true
                SwitchMode.OFF -> false
                SwitchMode.TOGGLE -> !current
            }
            val ok = Settings.System.putInt(
                context.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                if (enabled) 1 else 0,
            )
            if (ok) TaskOutcome(StepStatus.SUCCESS, if (enabled) "自动旋转已开启" else "自动旋转已关闭")
            else TaskOutcome(StepStatus.FAILURE, "自动旋转写入失败")
        } catch (_: Throwable) {
            TaskOutcome(StepStatus.FAILURE, "自动旋转写入失败")
        }
    }

    suspend fun globalAction(context: Context, parameter: String): TaskOutcome {
        val action = TaskParameters.token(meta("action.global_action"), parameter, "action")
        val service = FlowAccessibilityService.instance
            ?: return TaskOutcome(StepStatus.FAILURE, "无障碍服务未开启")
        val (code, label, minApi) = when (action) {
            "back" -> Triple(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "返回", 16)
            "home" -> Triple(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "主页", 16)
            "recents" -> Triple(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "最近任务", 16)
            "lock" -> Triple(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "锁屏", 28)
            "screenshot" -> Triple(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "截屏", 30)
            else -> return TaskOutcome(StepStatus.FAILURE, "系统导航参数无效")
        }
        if (Build.VERSION.SDK_INT < minApi) {
            return TaskOutcome(StepStatus.FAILURE, "当前系统版本不支持$label")
        }
        return if (service.perform(code)) TaskOutcome(StepStatus.SUCCESS, "已执行：$label")
        else TaskOutcome(StepStatus.FAILURE, "系统导航执行失败")
    }
}

private object FlashlightController {
    private var manager: CameraManager? = null
    private var selectedId: String? = null
    private var registered = false

    @Volatile
    private var on = false

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == selectedId) on = enabled
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == selectedId) on = false
        }
    }

    fun isOn(): Boolean = on

    fun set(context: Context, enabled: Boolean): String? {
        val cameraManager = context.getSystemService(CameraManager::class.java)
            ?: return "相机服务不可用"
        val id = try {
            cameraManager.cameraIdList.firstOrNull { cameraId ->
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Throwable) {
            null
        } ?: return "设备没有可用闪光灯"
        manager = cameraManager
        selectedId = id
        if (!registered) {
            try {
                cameraManager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
                registered = true
            } catch (_: Throwable) {
            }
        }
        return try {
            cameraManager.setTorchMode(id, enabled)
            on = enabled
            null
        } catch (failure: Throwable) {
            "闪光灯不可用：${failure.message ?: "系统拒绝设置"}"
        }
    }
}

internal object RootActions {
    suspend fun airplaneMode(context: Context, parameter: String): TaskOutcome {
        val mode = TaskParameters.mode(parameter)
        val current = if (mode == SwitchMode.TOGGLE) query("settings get global airplane_mode_on") == "1" else false
        val enabled = when (mode) {
            SwitchMode.ON -> true
            SwitchMode.OFF -> false
            SwitchMode.TOGGLE -> !current
        }
        val command = "settings put global airplane_mode_on ${if (enabled) 1 else 0}; " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        return run(command, if (enabled) "飞行模式已开启" else "飞行模式已关闭")
    }

    suspend fun mobileData(context: Context, parameter: String): TaskOutcome {
        val mode = TaskParameters.mode(parameter)
        var current = false
        if (mode == SwitchMode.TOGGLE) {
            val raw = query("settings get global mobile_data")
            if (raw != "0" && raw != "1") {
                return TaskOutcome(StepStatus.FAILURE, "无法读取移动数据状态")
            }
            current = raw == "1"
        }
        val enabled = when (mode) {
            SwitchMode.ON -> true
            SwitchMode.OFF -> false
            SwitchMode.TOGGLE -> !current
        }
        return run(
            "svc data ${if (enabled) "enable" else "disable"}",
            if (enabled) "移动数据已开启" else "移动数据已关闭",
        )
    }

    private suspend fun query(command: String): String? {
        val root = Root.check(request = true)
        if (!root.granted) return null
        val result = Root.runProcess(listOf(root.suPath, "-c", command))
        return if (!result.timedOut && result.exitCode == 0) result.output.trim() else null
    }

    private suspend fun run(command: String, success: String): TaskOutcome {
        val root = Root.check(request = true)
        if (!root.granted) return TaskOutcome(StepStatus.FAILURE, "缺少 Root 权限")
        val result = Root.runProcess(listOf(root.suPath, "-c", command))
        return when {
            result.timedOut -> TaskOutcome(StepStatus.FAILURE, "Root Shell 执行超时（30 秒）")
            result.exitCode != 0 -> TaskOutcome(StepStatus.FAILURE, "Root Shell 退出码 ${result.exitCode}\n${result.output}")
            else -> TaskOutcome(StepStatus.SUCCESS, success)
        }
    }
}
