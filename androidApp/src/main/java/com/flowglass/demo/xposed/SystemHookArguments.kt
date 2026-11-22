package com.flowglass.demo.xposed

/**
 * 系统钩子的参数布局在不同 Android 版本上不一样：
 *
 * - `KeyguardController.setKeyguardShown`：Android 12 是 `(boolean showing, boolean aod)`，
 *   13–15 变成 `(int displayId, boolean showing, boolean aod)`。固定读 `args[0]` 在 13–15 上
 *   拿到的是 displayId，锁屏事件会被静默丢掉。
 * - `MediaSessionService.onSessionPlaybackStateChanged`：12–14 只有
 *   `(MediaSessionRecord, boolean shouldUpdatePriority)`，没有 PlaybackState；固定读 `args[2]`
 *   永远是 null，播放状态永远不会上报。
 *
 * 这里只做「按值/类型找参数」，不猜具体版本号；调用方仍然要自己判空。
 */
internal object SystemHookArguments {
    /** 第一个 Boolean 参数即 keyguardShowing；aod 是第二个 Boolean。 */
    fun keyguardShowing(arguments: Array<Any?>): Boolean? =
        arguments.firstOrNull { it is Boolean } as? Boolean

    /** 找到携带播放状态的参数；12–14 没有，返回 null。 */
    fun playbackState(arguments: Array<Any?>): Any? =
        arguments.firstOrNull { value ->
            value != null && value.javaClass.name.let { name ->
                name.endsWith(".PlaybackState") || name.contains(".PlaybackState\$")
            }
        }
}
