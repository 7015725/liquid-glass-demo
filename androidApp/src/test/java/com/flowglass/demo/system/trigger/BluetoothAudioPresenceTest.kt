package com.flowglass.demo.system.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TRG-008：多台蓝牙音频设备时，只有最后一台移除才是「拔出」。
 */
class BluetoothAudioPresenceTest {
    @Test
    fun removingOneOfTwoHeadsetsIsNotAnUnplug() {
        val presence = BluetoothAudioPresence()
        assertTrue(presence.added("AA:01"))
        assertTrue(presence.added("AA:02"))
        assertFalse("第二台的第二个 profile 不是新设备", presence.added("AA:02"))
        assertFalse("还有一台连着，不能报拔出", presence.removed("AA:01"))
        assertFalse("第二台还剩一个 profile", presence.removed("AA:02"))
        assertTrue("最后一台最后一个 profile 移除才是拔出", presence.removed("AA:02"))
    }

    @Test
    fun aSecondProfileOnTheSameAddressIsNotAnUnplug() {
        val presence = BluetoothAudioPresence()
        // 同一副耳机的 A2DP 与 SCO 两个 profile，地址相同。
        assertTrue(presence.added("AA:01"))
        assertFalse(presence.added("AA:01"))
        assertFalse("只移除一个 profile 不能报拔出", presence.removed("AA:01"))
        assertTrue("两个 profile 都移除才是拔出", presence.removed("AA:01"))
        assertFalse(presence.removed("AA:01"))
    }

    @Test
    fun resetForgetsEveryDevice() {
        val presence = BluetoothAudioPresence()
        presence.added("AA:01")
        presence.reset()
        assertTrue("重置后再加入应重新产生 plugged 边沿", presence.added("AA:01"))
    }

    @Test
    fun receiverFlagsFollowTheBroadcastSource() {
        assertEquals(
            android.content.Context.RECEIVER_EXPORTED,
            receiverFlags(exported = true),
        )
        assertEquals(
            android.content.Context.RECEIVER_NOT_EXPORTED,
            receiverFlags(exported = false),
        )
    }
}
