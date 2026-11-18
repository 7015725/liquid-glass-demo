package com.flowglass.demo.system.exec

import android.os.Looper
import com.flowglass.demo.BuildConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConditionFileAccessTest {
    @Test
    fun existsRejectsMainThreadInDebugBuild() {
        assertTrue(BuildConfig.DEBUG)
        assertEqualsMainThread()
        val failure = assertThrows(IllegalStateException::class.java) {
            ConditionFileAccess.exists("/sdcard/Download")
        }
        assertTrue(failure.message.orEmpty().contains("main thread"))
    }

    private fun assertEqualsMainThread() {
        assertTrue(Looper.myLooper() == Looper.getMainLooper())
    }
}
