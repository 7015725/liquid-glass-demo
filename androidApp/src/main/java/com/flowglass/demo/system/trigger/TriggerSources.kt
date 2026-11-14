package com.flowglass.demo.system.trigger

import android.Manifest
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import com.flowglass.demo.system.FlowAccessibilityService
import com.flowglass.demo.system.Perms
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal typealias TriggerEmit = (kind: String, source: TriggerSource, data: Map<String, String>) -> Unit

/** Coalesces WiFi callbacks so a network switch does not look like disconnect + connect. */
internal class WifiWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val emit: TriggerEmit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private var pending: Job? = null
    private var lastConnected: Boolean? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivity?.registerNetworkCallback(request, callback)
        } catch (_: Throwable) {
        }
        schedule(immediate = true)
    }

    fun stop() {
        pending?.cancel()
        pending = null
        lastConnected = null
        try {
            connectivity?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
    }

    private fun schedule(immediate: Boolean = false) {
        pending?.cancel()
        pending = scope.launch {
            if (!immediate) delay(800)
            val connected = isWifiConnected()
            if (connected == lastConnected) return@launch
            lastConnected = connected
            emit(
                TriggerKinds.WIFI, TriggerSource.WIFI,
                mapOf(
                    "state" to if (connected) "connected" else "disconnected",
                    "ssid" to if (connected) currentSsid() else "",
                ),
            )
        }
    }

    private fun isWifiConnected(): Boolean {
        val manager = connectivity ?: return false
        val active = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(active)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun currentSsid(): String {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return ""
        }
        val info = try {
            @Suppress("DEPRECATION")
            wifi?.connectionInfo
        } catch (_: Throwable) {
            null
        } ?: return ""
        val name = info.ssid ?: return ""
        if (name == WifiManager.UNKNOWN_SSID || name.contains("unknown", ignoreCase = true)) return ""
        return name.trim('"')
    }
}

internal class BluetoothWatcher(
    private val context: Context,
    private val emit: TriggerEmit,
) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    emit(TriggerKinds.BLUETOOTH, TriggerSource.BLUETOOTH,
                        mapOf("state" to "connected", "name" to deviceName(device)))
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    emit(TriggerKinds.BLUETOOTH, TriggerSource.BLUETOOTH,
                        mapOf("state" to "disconnected", "name" to deviceName(device)))
            }
        }
    }

    fun start() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val current = manager?.adapter
        val canRead = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (current != null && current.isEnabled && canRead) {
            val devices = try {
                intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.GATT)
                    .flatMap { profile -> manager.getConnectedDevices(profile).orEmpty() }
                    .distinctBy { it.address }
            } catch (_: Throwable) {
                emptyList()
            }
            if (devices.isEmpty()) {
                emit(TriggerKinds.BLUETOOTH, TriggerSource.BLUETOOTH,
                    mapOf("state" to "disconnected", "name" to ""))
            } else {
                devices.forEach { device: BluetoothDevice ->
                    emit(TriggerKinds.BLUETOOTH, TriggerSource.BLUETOOTH,
                        mapOf("state" to "connected", "name" to deviceName(device)))
                }
            }
        } else {
            emit(TriggerKinds.BLUETOOTH, TriggerSource.BLUETOOTH,
                mapOf("state" to "disconnected", "name" to ""))
        }
        register(context, receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
    }

    private fun deviceName(device: BluetoothDevice?): String {
        if (device == null) return ""
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return ""
        return try {
            device.name.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}

internal class HeadsetWatcher(
    private val context: Context,
    private val emit: TriggerEmit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_HEADSET_PLUG) return
            val plugged = intent.getIntExtra("state", 0) == 1
            emit(
                TriggerKinds.HEADSET, TriggerSource.HEADSET,
                mapOf(
                    "state" to if (plugged) "plugged" else "unplugged",
                    "kind" to "wired",
                    "name" to intent.getStringExtra("name").orEmpty(),
                ),
            )
        }
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            addedDevices.filter { isBluetoothAudio(it) }.forEach {
                emit(TriggerKinds.HEADSET, TriggerSource.HEADSET,
                    mapOf("state" to "plugged", "kind" to "bluetooth", "name" to it.productName.toString()))
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            removedDevices.filter { isBluetoothAudio(it) }.forEach {
                emit(TriggerKinds.HEADSET, TriggerSource.HEADSET,
                    mapOf("state" to "unplugged", "kind" to "bluetooth", "name" to it.productName.toString()))
            }
        }
    }

    fun start() {
        val sticky = register(context, receiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        if (sticky != null) {
            receiver.onReceive(context, sticky)
        } else {
            emit(TriggerKinds.HEADSET, TriggerSource.HEADSET,
                mapOf("state" to "unplugged", "kind" to "wired", "name" to ""))
        }
        try {
            audio?.registerAudioDeviceCallback(devices, null)
            val bluetooth = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
                .filter { isBluetoothAudio(it) }
            if (bluetooth.isEmpty()) {
                emit(TriggerKinds.HEADSET, TriggerSource.HEADSET,
                    mapOf("state" to "unplugged", "kind" to "bluetooth", "name" to ""))
            } else {
                bluetooth.forEach {
                    emit(TriggerKinds.HEADSET, TriggerSource.HEADSET,
                        mapOf("state" to "plugged", "kind" to "bluetooth", "name" to it.productName.toString()))
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        try {
            audio?.unregisterAudioDeviceCallback(devices)
        } catch (_: Throwable) {
        }
    }

    private fun isBluetoothAudio(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> Build.VERSION.SDK_INT >= 31
        else -> false
    }
}

/**
 * Prefers accessibility window changes. When accessibility is off but usage access is granted,
 * polls the most recently used package instead, so the trigger remains usable.
 */
internal class AppForegroundWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val emit: TriggerEmit,
) {
    private var polling: Job? = null
    private val listener: (String) -> Unit = { packageName ->
        emit(TriggerKinds.APP, TriggerSource.APP, mapOf("app" to packageName))
    }

    fun start() {
        if (Perms.isA11yEnabled(context)) {
            FlowAccessibilityService.addForegroundListener(listener)
        } else if (Perms.hasUsageAccess(context)) {
            startPolling()
        }
    }

    fun stop() {
        FlowAccessibilityService.removeForegroundListener(listener)
        polling?.cancel()
        polling = null
    }

    private fun startPolling() {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return
        polling = scope.launch {
            var last = withContext(Dispatchers.IO) { latestPackage(usage) }
            while (isActive) {
                delay(2_000)
                val current = withContext(Dispatchers.IO) { latestPackage(usage) } ?: continue
                if (current != last) {
                    last = current
                    emit(TriggerKinds.APP, TriggerSource.APP, mapOf("app" to current))
                }
            }
        }
    }

    private fun latestPackage(usage: UsageStatsManager): String? {
        val end = System.currentTimeMillis()
        val stats = try {
            usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 60_000, end)
        } catch (_: Throwable) {
            null
        } ?: return null
        return stats.filter { it.lastTimeUsed > 0L }.maxByOrNull { it.lastTimeUsed }?.packageName
    }
}

private fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter): Intent? = try {
    if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, filter)
    }
} catch (_: Throwable) {
    null
}
