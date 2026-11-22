package com.flowglass.demo.xposed

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HOOKS-009/010：按值/类型定位参数，覆盖 Android 12 与 13–15 的 keyguard 布局，以及
 * 12–14（两个参数）与带 PlaybackState 的媒体回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemHookArgumentsTest {
    @Test
    fun keyguardShowingIsFoundOnBothLayouts() {
        assertEquals(true, SystemHookArguments.keyguardShowing(arrayOf<Any?>(true, false)))
        assertEquals(true, SystemHookArguments.keyguardShowing(arrayOf<Any?>(0, true, false)))
        assertEquals(false, SystemHookArguments.keyguardShowing(arrayOf<Any?>(0, false, true)))
        assertNull(SystemHookArguments.keyguardShowing(arrayOf<Any?>(0, 1)))
        assertNull(SystemHookArguments.keyguardShowing(emptyArray()))
    }

    @Test
    fun playbackStateIsFoundOnlyWhenTheCallbackCarriesOne() {
        val state = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 1_000L, 1f)
            .build()
        assertEquals(
            state,
            SystemHookArguments.playbackState(arrayOf<Any?>(Any(), state, true)),
        )
        // 12–14：只有 record 与 shouldUpdatePriority，没有 PlaybackState，必须从 record 上读。
        assertEquals(
            state,
            SystemHookArguments.playbackState(arrayOf<Any?>(Any(), false), Any()) { _, name ->
                if (name == "getPlaybackState") state else null
            },
        )
        assertNull(
            SystemHookArguments.playbackState(arrayOf<Any?>(Any(), false), null) { _, _ -> state },
        )
        assertTrue(SystemHookArguments.playbackState(arrayOf<Any?>(state)) === state)
    }
}
