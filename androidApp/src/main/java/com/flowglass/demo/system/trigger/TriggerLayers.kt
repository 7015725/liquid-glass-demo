package com.flowglass.demo.system.trigger

import com.flowglass.engine.ExecutionLayer
import com.flowglass.engine.trigger.TriggerTypes

/**
 * Host trigger implementation registry. PR1 has one provider per trigger source (the existing
 * normal watchers); later PRs add LSPosed/Root providers for `trigger.app_foreground` without
 * changing component metadata again.
 */
internal object TriggerLayers {
    fun hasImplementation(typeId: String, layer: ExecutionLayer): Boolean = when (layer) {
        ExecutionLayer.NORMAL -> TriggerTypes.sourceOf(typeId) != null
        ExecutionLayer.XPOSED, ExecutionLayer.ROOT -> false
    }
}
