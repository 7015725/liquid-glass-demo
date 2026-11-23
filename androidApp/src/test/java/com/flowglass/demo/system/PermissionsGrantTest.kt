package com.flowglass.demo.system

import android.Manifest
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-SHELL-03：定位授权必须同时请求 fine/coarse，单 fine 在部分 Android 12 上不弹框。 */
class PermissionsGrantTest {
    @Test
    fun locationRequestsBothAccuracyLevels() {
        val item = Perms.all.single { it.key == "location" }
        val grant = item.grant as Grant.Runtime
        assertTrue(grant.permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(grant.permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
