package com.flowglass.demo.system.exec

import android.content.Context
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.RootInfo
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem

/**
 * Root-layer action implementations. [root] is selected once by [LayerResolver] and passed in;
 * these handlers never call Root.check, so a run cannot trigger a second authorization prompt.
 */
internal object RootTasks {
    suspend fun run(
        context: Context,
        cmd: Cmd,
        task: CommandItem,
        root: RootInfo,
    ): TaskOutcome {
        val typeId = task.typeId ?: com.flowglass.engine.BuiltInActions.metadata
            .firstOrNull { it.name == task.name }?.typeId
        return when (typeId) {
            "action.root_shell" -> rootShell(root, task.parameter)
            "action.airplane_mode" -> RootActions.airplaneMode(context, task.parameter, root)
            "action.mobile_data" -> RootActions.mobileData(context, task.parameter, root)
            "action.wifi_switch" -> RadioTask.runRoot(context, "开关 WiFi", task.parameter, root)
            "action.bluetooth_switch" -> RadioTask.runRoot(context, "开关蓝牙", task.parameter, root)
            "action.launch_app" -> {
                require(task.parameter.isNotBlank()) { "参数格式不正确：期望 应用包名或应用名称" }
                LaunchableApps.launchRoot(context, task.parameter, root)
                TaskOutcome(StepStatus.SUCCESS, "已启动应用：${task.parameter}（Root）")
            }
            else -> TaskOutcome(StepStatus.FAILURE, "该动作没有 Root 实现")
        }
    }

    private suspend fun rootShell(root: RootInfo, command: String): TaskOutcome {
        require(command.isNotBlank()) { "参数格式不正确：期望 非空 Shell 命令" }
        val result = Root.runProcess(listOf(root.suPath, "-c", command))
        return when {
            result.timedOut -> TaskOutcome(StepStatus.FAILURE, "Shell 执行超时（30 秒）\n${result.output}")
            result.exitCode != 0 -> TaskOutcome(StepStatus.FAILURE, "Shell 退出码 ${result.exitCode}\n${result.output}")
            else -> TaskOutcome(StepStatus.SUCCESS, result.output.ifEmpty { "Shell 执行完成" }, result.output)
        }
    }
}
