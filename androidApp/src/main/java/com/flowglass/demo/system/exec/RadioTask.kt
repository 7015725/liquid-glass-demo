package com.flowglass.demo.system.exec

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.flowglass.demo.system.Root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object RadioTask {
    fun command(name: String, mode: SwitchMode, currentlyEnabled: Boolean, sdk: Int): String {
        val enabled = when (mode) { SwitchMode.ON -> true; SwitchMode.OFF -> false; SwitchMode.TOGGLE -> !currentlyEnabled }
        val api = if (name == "开关 WiFi") "svc wifi" else if (sdk >= 33) "cmd bluetooth_manager" else "svc bluetooth"
        return "$api ${if (enabled) "enable" else "disable"}"
    }
    suspend fun run(context: Context, name: String, parameter: String): TaskOutcome {
        val mode = TaskParameters.mode(parameter)
        val root = Root.check(request = true)
        if (root.granted) return withContext(Dispatchers.IO) {
            val currentlyEnabled = if (mode != SwitchMode.TOGGLE) false else if (name == "开关 WiFi") {
                checkNotNull(context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)) { "WiFi 服务不可用" }.isWifiEnabled
            } else {
                check(Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "缺少蓝牙连接权限" }
                val manager = checkNotNull(context.getSystemService(android.bluetooth.BluetoothManager::class.java)) { "蓝牙服务不可用" }
                checkNotNull(manager.adapter) { "设备不支持蓝牙" }.isEnabled
            }
            val command = command(name, mode, currentlyEnabled, Build.VERSION.SDK_INT)
            val result = Root.runProcess(listOf(root.suPath, "-c", command))
            when {
                result.timedOut -> TaskOutcome(StepStatus.FAILURE, "无线设置执行超时")
                result.exitCode != 0 -> TaskOutcome(StepStatus.FAILURE, "无线设置退出码 ${result.exitCode}\n${result.output}")
                else -> TaskOutcome(StepStatus.SUCCESS, "已执行：$command")
            }
        }
        val wifi = name == "开关 WiFi"
        val action = if (wifi) {
            if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        } else Settings.ACTION_BLUETOOTH_SETTINGS
        withContext(Dispatchers.Main.immediate) {
            try { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (error: ActivityNotFoundException) {
                if (!wifi || action == Settings.ACTION_WIFI_SETTINGS) throw error
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return TaskOutcome(StepStatus.SUCCESS, "已打开系统面板，需手动确认")
    }
}
