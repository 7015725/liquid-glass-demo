package com.flowglass.demo.system.exec

import android.content.Context
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.engine.ExecutionLayer

/**
 * Host-side implementation registry. A structural test compares this registry with
 * [com.flowglass.engine.BuiltInActions.metadata] so a component cannot declare a layer without
 * a handler, or ship a handler without declaring it.
 */
internal object ActionLayers {
    val normalTypes: Set<String> = setOf(
        "action.wifi_switch", "action.bluetooth_switch", "action.dnd_switch",
        "action.set_brightness", "action.set_volume", "action.launch_app",
        "action.post_notification", "action.copy_clipboard", "action.delay",
        "action.http_request", "action.vibrate", "action.flashlight", "action.ringer_mode",
        "action.media_control", "action.open_url", "action.speak", "action.toast",
        "action.screen_timeout", "action.auto_rotate", "action.global_action",
    )

    val rootTypes: Set<String> = setOf(
        "action.wifi_switch", "action.bluetooth_switch", "action.launch_app",
        "action.root_shell", "action.airplane_mode", "action.mobile_data",
    )

    fun hasImplementation(typeId: String, layer: ExecutionLayer): Boolean = when (layer) {
        ExecutionLayer.NORMAL -> typeId in normalTypes
        ExecutionLayer.ROOT -> typeId in rootTypes
        ExecutionLayer.XPOSED -> false
    }

    suspend fun run(
        context: Context,
        cmd: Cmd,
        task: CommandItem,
        selection: LayerSelection,
    ): TaskOutcome = when (selection.layer) {
        ExecutionLayer.NORMAL -> NormalTasks.run(context, cmd, task)
        ExecutionLayer.ROOT -> {
            val root = selection.rootInfo
                ?: return TaskOutcome(StepStatus.FAILURE, "Root 层已选中，但授权信息缺失")
            RootTasks.run(context, cmd, task, root)
        }
        ExecutionLayer.XPOSED -> TaskOutcome(StepStatus.FAILURE, "该动作没有 LSPosed 实现")
    }
}
