package com.flowglass.demo.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BluetoothNameField(current: String, tag: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        val missing = Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        if (missing) {
            message = "缺少蓝牙连接权限，仍可手动输入设备名称。"
            return@LaunchedEffect
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val adapter = context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
                adapter?.bondedDevices.orEmpty()
                    .mapNotNull { it.name?.takeIf(String::isNotBlank) }.distinct().sorted()
            }
        }
        devices = result.getOrDefault(emptyList())
        message = result.exceptionOrNull()?.let { "读取已配对设备失败，仍可手动输入。" }
    }
    ParameterTextField(current, tag, onValue)
    T("留空匹配任意设备；名称按包含匹配，仍可直接输入。", 12.sp, color = C.OnSurfaceVar)
    message?.let { T(it, 12.sp, color = if (devices.isEmpty()) C.Bad else C.OnSurfaceVar) }
    if (devices.isNotEmpty()) {
        T("已配对设备", 13.sp)
        FilterChips(devices, devices.indexOf(current), onSelect = { onValue(devices[it]) })
    }
}

@Composable
internal fun WifiNameField(current: String, tag: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    var networks by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            message = "读取 WiFi 名称需要定位权限，仍可手动输入。"
            return@LaunchedEffect
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val manager = context.applicationContext.getSystemService(WifiManager::class.java)
                    ?: return@withContext emptyList<String>()
                val currentSsid = manager.connectionInfo?.ssid?.trim('"')
                    ?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
                runCatching { manager.startScan() }
                val scanned = manager.scanResults.orEmpty()
                    .mapNotNull { it.SSID?.trim('"')?.takeIf(String::isNotBlank) }
                (listOfNotNull(currentSsid) + scanned).distinct().sorted()
            }
        }
        networks = result.getOrDefault(emptyList())
        message = result.exceptionOrNull()?.let { "读取 WiFi 列表失败，仍可手动输入。" }
    }
    ParameterTextField(current, tag, onValue)
    T("留空匹配任意 WiFi；仍可直接输入网络名称。", 12.sp, color = C.OnSurfaceVar)
    message?.let { T(it, 12.sp, color = if (networks.isEmpty()) C.Bad else C.OnSurfaceVar) }
    if (networks.isNotEmpty()) {
        T("附近网络", 13.sp)
        FilterChips(networks, networks.indexOf(current), onSelect = { onValue(networks[it]) })
    }
}
