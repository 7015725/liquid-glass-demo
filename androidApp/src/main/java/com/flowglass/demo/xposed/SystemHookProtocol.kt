package com.flowglass.demo.xposed

/**
 * Channel names shared by the LSPosed system-framework hook and the app process.
 * The hook only reads information and sends these explicit broadcasts; it never changes
 * system-server state.
 */
object SystemHookProtocol {
    const val APP_PACKAGE = "com.flowglass.demo"
    const val ACTION_STATE = "com.flowglass.demo.SYSTEM_HOOK_STATE"
    const val ACTION_CONFIG = "com.flowglass.demo.SYSTEM_HOOK_CONFIG"
    const val ACTION_LOADED = "com.flowglass.demo.SYSTEM_HOOK_LOADED"
    const val ACTION_FOREGROUND = "com.flowglass.demo.SYSTEM_FOREGROUND"
    const val ACTION_EVENT = "com.flowglass.demo.SYSTEM_EVENT"
    const val ACTION_HEARTBEAT = "com.flowglass.demo.SYSTEM_HOOK_HEARTBEAT"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_APP_KNOWS_LOADED = "app_knows_loaded"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_USER = "user"
    const val EXTRA_TIME = "time"
    const val EXTRA_HOOKS = "hooks"
    const val EXTRA_FAMILIES = "families"
    const val EXTRA_AVAILABLE_FAMILIES = "available_families"
    const val EXTRA_WATCHED = "watched"
    const val EXTRA_EVENT = "event"
    const val EXTRA_SEQ = "seq"

    /** Event names carried by [ACTION_EVENT]. */
    const val EVENT_FOREGROUND = "foreground"
    const val EVENT_PROCESS_STARTED = "process_started"
    const val EVENT_PROCESS_ENDED = "process_ended"
    const val EVENT_APP_CRASH = "app_crash"
    const val EVENT_APP_ANR = "app_anr"
    const val EVENT_KEYGUARD_LOCKED = "keyguard_locked"
    const val EVENT_KEYGUARD_UNLOCKED = "keyguard_unlocked"
    const val EVENT_MEDIA_PLAYING = "media_playing"
    const val EVENT_MEDIA_PAUSED = "media_paused"
    const val EVENT_MEDIA_STOPPED = "media_stopped"

    const val PERMISSION = "com.flowglass.demo.permission.SYSTEM_HOOK"
    const val SYSTEM_RECEIVER = "com.flowglass.demo.system.trigger.SystemHookReceiver"
}
