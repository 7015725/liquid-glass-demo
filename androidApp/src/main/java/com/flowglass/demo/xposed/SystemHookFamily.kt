package com.flowglass.demo.xposed

/**
 * One independently subscribed family of read-only system-server observations.
 *
 * The bit values are part of the protected app <-> system-hook protocol; do not renumber an
 * existing family. New families must be appended so an older hook and a newer app can still
 * negotiate the subset they both understand.
 */
internal enum class SystemHookFamily(val mask: Int, val label: String) {
    FOREGROUND(1 shl 0, "前台应用"),
    PROCESS(1 shl 1, "应用进程"),
    CRASH(1 shl 2, "崩溃 / ANR"),
    KEYGUARD(1 shl 3, "锁屏 / 解锁"),
    MEDIA(1 shl 4, "媒体会话"),
    FOREGROUND_SERVICE(1 shl 5, "前台服务"),
    CLIPBOARD(1 shl 6, "剪贴板"),
    ;

    companion object {
        val defaultMask: Int = FOREGROUND.mask

        fun fromMask(mask: Int): Set<SystemHookFamily> =
            entries.filter { mask and it.mask != 0 }.toSet()

        fun maskOf(families: Iterable<SystemHookFamily>): Int =
            families.fold(0) { result, family -> result or family.mask }
    }
}
