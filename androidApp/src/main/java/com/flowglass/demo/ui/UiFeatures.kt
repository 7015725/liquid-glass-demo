package com.flowglass.demo.ui

/**
 * Temporary UI gates for features that are not wired to the runtime yet.
 *
 * The run-mode selector persists 省电 / 均衡 / 极速, but TriggerHost currently ignores it.
 * Keep the state for forward compatibility, but do not present the selector until the
 * polling policy is actually connected and verified.
 */
internal object UiFeatures {
    const val SHOW_RUN_MODE = false
}
