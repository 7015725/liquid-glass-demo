package com.flowglass.demo.ui

import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The settings row must follow the installed package, not a hand-copied release string. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppVersionNameTest {
    @Test
    fun readsTheVersionNameFromPackageManager() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }

        assertEquals(info.versionName, appVersionName(ctx))
        assertTrue(appVersionName(ctx).isNotBlank())
    }
}
