package com.flowglass.demo.system.a11y

/**
 * The whole rebind decision, with no Android dependency. The hook only reads state and calls this,
 * so every "do not fight the user" rule is unit tested instead of living in a callback.
 */
internal object A11yKeepAlivePolicy {
    const val REASON_NOT_TARGET = "not_target"
    const val REASON_DISABLED = "disabled"
    const val REASON_MASTER_OFF = "master_off"
    const val REASON_COMPONENT_REMOVED = "component_removed"
    const val REASON_APP_STOPPED = "app_stopped"
    const val REASON_ALREADY_BOUND = "already_bound"
    const val REASON_LIMIT = "limit"

    const val MAX_ATTEMPTS = 3

    data class Result(val rebind: Boolean, val reason: String)

    fun decide(
        isTarget: Boolean,
        keepAliveEnabled: Boolean,
        a11yMasterEnabled: Boolean,
        componentEnabled: Boolean,
        appStopped: Boolean,
        alreadyBound: Boolean,
        attempts: Int,
        maxAttempts: Int = MAX_ATTEMPTS,
    ): Result = when {
        !isTarget -> Result(false, REASON_NOT_TARGET)
        !keepAliveEnabled -> Result(false, REASON_DISABLED)
        !a11yMasterEnabled -> Result(false, REASON_MASTER_OFF)
        !componentEnabled -> Result(false, REASON_COMPONENT_REMOVED)
        appStopped -> Result(false, REASON_APP_STOPPED)
        alreadyBound -> Result(false, REASON_ALREADY_BOUND)
        attempts >= maxAttempts -> Result(false, REASON_LIMIT)
        else -> Result(true, "")
    }
}
