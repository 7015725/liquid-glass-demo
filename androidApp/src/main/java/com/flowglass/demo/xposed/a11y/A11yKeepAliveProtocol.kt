package com.flowglass.demo.xposed.a11y

/** Channel names for the multi-target accessibility keep-alive. Pure constants: JVM testable. */
object A11yKeepAliveProtocol {
    const val APP_PACKAGE = "com.flowglass.demo"
    const val RECEIVER = "com.flowglass.demo.system.trigger.A11yKeepAliveReceiver"
    const val PERMISSION = "com.flowglass.demo.permission.SYSTEM_HOOK"

    /** system -> app */
    const val ACTION_LOADED = "com.flowglass.demo.A11Y_KEEPALIVE_LOADED"
    const val ACTION_STATUS = "com.flowglass.demo.A11Y_KEEPALIVE_STATUS"

    /** app -> android */
    const val ACTION_CONFIG = "com.flowglass.demo.A11Y_KEEPALIVE_CONFIG"

    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_TARGETS = "targets"
    const val EXTRA_APP_KNOWS_LOADED = "app_knows_loaded"
    const val EXTRA_HOOKS = "hooks"
    const val EXTRA_SUPPORTED = "supported"
    const val EXTRA_COMPONENT = "component"
    const val EXTRA_STATE = "state"
    const val EXTRA_REASON = "reason"
    const val EXTRA_AT = "at"

    val STATES = setOf("active", "disconnected", "repairing", "repaired", "skipped", "limit", "unsupported", "error")
}
