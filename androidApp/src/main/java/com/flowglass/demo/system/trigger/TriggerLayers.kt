package com.flowglass.demo.system.trigger

import android.content.Context
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.xposed.ModuleStatus
import com.flowglass.engine.ExecutionLayer
import com.flowglass.engine.trigger.TriggerTypes

/**
 * Host trigger implementation registry. Normal watchers cover every source; the app-foreground
 * trigger additionally has a system-hook provider and a clone-only Root poller.
 */
internal object TriggerLayers {
    private val xposedOnly = setOf(
        TriggerTypes.APP_PROCESS,
        TriggerTypes.APP_CRASH,
        TriggerTypes.KEYGUARD,
        TriggerTypes.MEDIA,
        TriggerTypes.FOREGROUND_SERVICE,
    )

    fun requiresSystemHook(typeId: String): Boolean = typeId in xposedOnly

    fun hasImplementation(typeId: String, layer: ExecutionLayer): Boolean = when (layer) {
        ExecutionLayer.NORMAL ->
            TriggerTypes.sourceOf(typeId) != null && typeId !in xposedOnly && typeId != TriggerTypes.METHOD_HOOK
        ExecutionLayer.XPOSED ->
            typeId == TriggerTypes.APP_FOREGROUND || typeId == TriggerTypes.METHOD_HOOK || typeId in xposedOnly
        ExecutionLayer.ROOT -> typeId == TriggerTypes.APP_FOREGROUND
    }

    /** The system hook wins when the user enabled it and the module is active. */
    fun appForegroundLayer(context: Context): ExecutionLayer = when {
        SystemHookSettings.isAlive(context) && ModuleStatus.isActive() -> ExecutionLayer.XPOSED
        Root.findSu() != null -> ExecutionLayer.ROOT
        else -> ExecutionLayer.NORMAL
    }
}
