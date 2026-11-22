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

    /** 找到实参里携带的播放状态；12–14 的回调没有这个参数。 */
    fun playbackState(arguments: Array<Any?>): Any? =
        arguments.firstOrNull { value ->
            value != null && value.javaClass.name.let { name ->
                name.endsWith(".PlaybackState") || name.contains(".PlaybackState\$")
            }
        }

    /**
     * 12–14 的 onSessionPlaybackStateChanged 只有 (record, shouldUpdatePriority)，状态只能从
     * record 上读；15 之后才可能直接带 PlaybackState 实参。两条路都走不通才放弃。
     */
    fun playbackState(
        arguments: Array<Any?>,
        record: Any?,
        call: (Any, String) -> Any?,
    ): Any? = playbackState(arguments)
        ?: record?.let { runCatching { call(it, "getPlaybackState") }.getOrNull() }
}
