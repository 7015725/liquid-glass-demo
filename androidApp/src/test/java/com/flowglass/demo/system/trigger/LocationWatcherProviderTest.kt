package com.flowglass.demo.system.trigger

import android.Manifest
import android.content.Intent
import android.os.Looper
import android.location.LocationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager

/** TRG-010：启动时定位关闭，之后用户打开定位，watcher 必须恢复请求而不是一直空转。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationWatcherProviderTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val location get() = app.getSystemService(LocationManager::class.java)
    private val shadow get() = Shadows.shadowOf(location)

    private fun grantLocation() {
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun setProviders(network: Boolean, gps: Boolean) {
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, network)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, gps)
    }

    @Test
    fun enablingLocationLaterStartsTheWatcher() {
        grantLocation()
        setProviders(network = false, gps = false)
        val watcher = LocationWatcher(app) { _, _, _ -> }
        watcher.start()
        assertFalse("没有 provider 时不应假装在监听", watcher.listening)

        setProviders(network = true, gps = false)
        app.sendBroadcast(Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue("打开定位后必须恢复监听", watcher.listening)
        watcher.stop()
    }
}
