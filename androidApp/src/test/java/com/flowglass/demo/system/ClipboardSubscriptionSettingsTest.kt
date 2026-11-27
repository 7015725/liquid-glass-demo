package com.flowglass.demo.system

import android.content.Context
import com.flowglass.demo.xposed.SystemHookFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardSubscriptionSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("system_hook", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SystemHookSettings.resetBroadcastThrottle()
        SystemHookSettings.broadcastConfig(context, 0, emptyList())
    }

    @Test
    fun auxiliaryClipboardSurvivesClearingRuleSubscriptions() {
        SystemHookSettings.setAuxiliaryFamily(context, SystemHookFamily.CLIPBOARD, true)
        assertEquals(SystemHookFamily.CLIPBOARD.mask, SystemHookSettings.subscribedFamilies(context))

        SystemHookSettings.broadcastConfig(context, SystemHookFamily.FOREGROUND.mask, emptyList())
        SystemHookSettings.clearSubscriptions(context)

        assertEquals(SystemHookFamily.CLIPBOARD.mask, SystemHookSettings.subscribedFamilies(context))
        assertTrue((SystemHookSettings.subscribedFamilies(context) and SystemHookFamily.CLIPBOARD.mask) != 0)
    }

    @Test
    fun disablingAuxiliaryClipboardRemovesOnlyItsBit() {
        SystemHookSettings.setAuxiliaryFamily(context, SystemHookFamily.CLIPBOARD, true)
        SystemHookSettings.broadcastConfig(context, SystemHookFamily.PROCESS.mask, emptyList())
        SystemHookSettings.setAuxiliaryFamily(context, SystemHookFamily.CLIPBOARD, false)
        assertEquals(SystemHookFamily.PROCESS.mask, SystemHookSettings.subscribedFamilies(context))
    }
}
