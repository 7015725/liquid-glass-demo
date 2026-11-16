package com.flowglass.demo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ParameterCodec

/**
 * Location trigger editor. Coordinates stay in the canonical `纬度,经度` text parameter so old
 * rules and manual editing remain readable; the radius is an integer in metres.
 */
@Composable
internal fun LocationParameterEditor(
    meta: ComponentMeta,
    item: CommandItem,
    onParameter: (String) -> Unit,
    onDone: () -> Unit,
    showDone: Boolean = true,
) {
    var fields by remember(item.id) { mutableStateOf(ParameterForm.fields(meta, item.parameter)) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var locationMessage by remember(item.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun update(key: String, value: String) {
        val next = fields + (key to value)
        fields = next
        ParameterForm.compose(meta, next)
            .onSuccess { onParameter(it); error = null }
            .onFailure { error = it.message?.replace('\n', ' ')?.take(120) }
    }

    meta.params.forEach { spec ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(ParameterForm.fieldLabel(spec), 14.sp, FontWeight.SemiBold)
            ParameterTextField(fields[spec.key].orEmpty(), "param-${spec.key}") { update(spec.key, it) }
            if (spec.hint.isNotBlank()) T(spec.hint, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
        }
    }

    CurrentLocationButton { location ->
        if (location == null) {
            locationMessage = "无法读取当前位置，请检查定位权限和定位开关"
        } else {
            val text = "${location.latitude},${location.longitude}"
            locationMessage = "已填入当前位置：$text"
            update("location", text)
        }
    }
    locationMessage?.let { T(it, 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp) }
    error?.let { T("参数无效：$it", 12.sp, color = C.Bad) }
    if (showDone) EditorPill("完成", "完成", action = onDone)
}

@Composable
private fun CurrentLocationButton(onResult: (Location?) -> Unit) {
    val context = LocalContext.current
    EditorPill("填入当前位置", "填入当前位置") {
        currentLocation(context, onResult)
    }
}

internal fun currentLocation(context: Context, onResult: (Location?) -> Unit) {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        onResult(null)
        return
    }
    val manager = context.getSystemService(LocationManager::class.java)
    if (manager == null) {
        onResult(null)
        return
    }
    val providers = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    ).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    val last = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }
    if (last != null) {
        onResult(last)
        return
    }
    val provider = providers.firstOrNull()
    if (provider == null) {
        onResult(null)
        return
    }
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onResult(location)
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) = onResult(null)
    }
    try {
        if (Build.VERSION.SDK_INT >= 30) {
            manager.getCurrentLocation(
                provider, null, context.mainExecutor,
            ) { location -> listener.onLocationChanged(location ?: return@getCurrentLocation) }
        } else {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    } catch (_: SecurityException) {
        onResult(null)
    }
}
