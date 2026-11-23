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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
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

/**
 * Low-frequency location sampling for the enter/exit triggers. It only runs while a location
 * rule is listening and asks the platform for a network fix every two minutes. No Play services
 * and no continuous GPS tracking: a foreground service does not need ACCESS_BACKGROUND_LOCATION.
 */
internal class LocationWatcher(
    private val context: Context,
    private val emit: TriggerEmit,
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var started = false
    private var providerReceiver: BroadcastReceiver? = null

    /** Test seam: true while location updates are actually requested. */
    internal val listening: Boolean get() = listener != null

    fun start() {
        val lm = manager ?: return
        armProviderReceiver()
        if (started) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) = publish(location)
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { stopUpdates() }
        }
        listener = callback
        try {
            lm.getLastKnownLocation(provider)?.let(::publish)
            lm.requestLocationUpdates(provider, INTERVAL_MS, 0f, callback, Looper.getMainLooper())
            started = true
        } catch (_: SecurityException) {
            listener = null
        }
    }

    fun stop() {
        stopUpdates()
        providerReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        providerReceiver = null
    }

    /**
     * Location can be switched off while the trigger host is alive. Registering once for provider
     * changes lets the watcher resume when the user turns location back on, instead of staying
     * "listening" without a provider until the host is rebuilt.
     */
    private fun armProviderReceiver() {
        if (providerReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION && !started) start()
            }
        }
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        val registered = try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, receiverFlags(exported = false))
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            true
        } catch (_: Throwable) {
            false
        }
        if (registered) providerReceiver = receiver
    }

    private fun stopUpdates() {
        val callback = listener
        listener = null
        started = false
        if (callback != null) runCatching { manager?.removeUpdates(callback) }
    }

    private fun publish(location: Location) {
        emit(
            TriggerKinds.LOCATION,
            TriggerSource.LOCATION,
            mapOf("lat" to location.latitude.toString(), "lng" to location.longitude.toString()),
        )
    }

    private companion object {
        const val INTERVAL_MS = 120_000L
    }
}

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
        }, exported = true)
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

/**
 * TRG-008：多台蓝牙音频设备时，拔掉任意一台不等于耳机全部拔出。这里按地址维护当前集合，
 * 只有集合从非空变空才产生 unplugged 边沿。
 */
internal class BluetoothAudioPresence {
    // 同一副耳机会同时暴露 A2DP 与 SCO 两个 AudioDeviceInfo，地址相同；用计数而不是集合，
    // 否则移除其中一个 profile 就会被当成「全部拔出」。
    private val counts = mutableMapOf<String, Int>()

    fun key(device: AudioDeviceInfo): String {
        // getAddress() 是 API 28 才有的；26/27 用设备 id 兜底。
        val address = if (Build.VERSION.SDK_INT >= 28) device.address else null
        return address?.takeIf { it.isNotBlank() } ?: "id:${device.id}"
    }

    /** 返回是否真的发生了「从无到有」的 plugged 边沿。 */
    @Synchronized
    fun added(key: String): Boolean {
        val previous = counts[key] ?: 0
        counts[key] = previous + 1
        return previous == 0
    }

    /** 返回是否真的发生了「从有到无」的 unplugged 边沿（最后一个 profile/设备移除）。 */
    @Synchronized
    fun removed(key: String): Boolean {
        val previous = counts[key] ?: return false
        if (previous > 1) {
            counts[key] = previous - 1
            return false
        }
        counts.remove(key)
        return counts.isEmpty()
    }

    @Synchronized
    fun reset() = counts.clear()
}

internal class HeadsetWatcher(
    private val context: Context,
    private val emit: TriggerEmit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val bluetoothPresence = BluetoothAudioPresence()

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
                if (!bluetoothPresence.added(bluetoothPresence.key(it))) return@forEach
                emit(TriggerKinds.HEADSET, TriggerSource.HEADSET,
                    mapOf("state" to "plugged", "kind" to "bluetooth", "name" to it.productName.toString()))
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            removedDevices.filter { isBluetoothAudio(it) }.forEach {
                if (!bluetoothPresence.removed(bluetoothPresence.key(it))) return@forEach
                // 只有最后一台蓝牙音频设备移除时才是真正的「拔出」。
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
            // 注册 AudioDeviceCallback 后平台会立刻回放当前设备列表（MSG_DEVICE_LIST_INIT），
            // plugged 边沿由这次回放统一产生。这里只处理「一台都没有」的基线，否则两条路径
            // 各加一次计数，拔出时计数永远不归零，unplugged 再也不会上报。
            val bluetooth = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
                .filter { isBluetoothAudio(it) }
            if (bluetooth.isEmpty()) {
                emit(TriggerKinds.HEADSET, TriggerSource.HEADSET,
                    mapOf("state" to "unplugged", "kind" to "bluetooth", "name" to ""))
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
        bluetoothPresence.reset()
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

private fun register(
    context: Context,
    receiver: BroadcastReceiver,
    filter: IntentFilter,
    exported: Boolean = false,
): Intent? = try {
    if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, receiverFlags(exported))
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, filter)
    }
} catch (_: Throwable) {
    null
}

/**
 * 系统/特权进程发出的广播（例如蓝牙进程的 ACL_CONNECTED）在 Android 13+ 用
 * RECEIVER_NOT_EXPORTED 注册会收不到；这些 action 在平台里是 protected broadcast，
 * 普通应用无法伪造，所以按来源拆分注册比给所有接收器放开导出安全。
 */
internal fun receiverFlags(exported: Boolean): Int =
    if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
