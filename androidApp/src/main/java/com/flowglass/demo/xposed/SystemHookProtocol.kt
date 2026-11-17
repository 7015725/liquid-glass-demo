package com.flowglass.demo.xposed

/**
 * Channel names shared by the LSPosed system-framework hook and the app process.
 * The hook only reads information and sends these explicit broadcasts; it never changes
 * system-server state.
 */
object SystemHookProtocol {
    const val APP_PACKAGE = "com.flowglass.demo"
    const val ACTION_STATE = "com.flowglass.demo.SYSTEM_HOOK_STATE"
    const val ACTION_FOREGROUND = "com.flowglass.demo.SYSTEM_FOREGROUND"
    const val ACTION_HEARTBEAT = "com.flowglass.demo.SYSTEM_HOOK_HEARTBEAT"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_USER = "user"
    const val EXTRA_TIME = "time"
    const val EXTRA_HOOKS = "hooks"
    const val PERMISSION = "com.flowglass.demo.permission.SYSTEM_HOOK"
    const val SYSTEM_RECEIVER = "com.flowglass.demo.system.trigger.SystemHookReceiver"
}
