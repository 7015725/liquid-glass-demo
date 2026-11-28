package com.flowglass.demo.system.exec

import android.content.Context
import com.flowglass.demo.system.RootInfo
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import kotlinx.coroutines.CancellationException

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
            "action.dnd_switch" -> RootActions.dnd(task.parameter, root)
            "action.set_brightness" -> RootActions.brightness(task.parameter, root)
            "action.screen_timeout" -> RootActions.screenTimeout(task.parameter, root)
            "action.auto_rotate" -> RootActions.autoRotate(task.parameter, root)
            "action.global_action" -> RootActions.globalAction(task.parameter, root)
            "action.input_text" -> TextInputTask.run(task.parameter, root)
            "action.launch_app" -> {
                require(task.parameter.isNotBlank()) { "参数格式不正确：期望 应用包名或应用名称" }
                LaunchableApps.launchRoot(context, task.parameter, root)
                TaskOutcome(StepStatus.SUCCESS, "已启动应用：${task.parameter}（Root）")
            }
            "action.force_stop" -> forceStop(context, task.parameter, root)
            else -> TaskOutcome(StepStatus.FAILURE, "该动作没有 Root 实现")
        }
    }

    private suspend fun forceStop(context: Context, raw: String, root: RootInfo): TaskOutcome {
        val target = ForceStopGuard.parse(raw, ForceStopGuard.launcherPackages(context))
        val result = RootSessions.run(root, target.command)
        return when {
            result.timedOut -> TaskOutcome(StepStatus.FAILURE, "强制停止超时（30 秒）\n${result.combinedOutput}")
            result.truncated -> TaskOutcome(StepStatus.FAILURE, "强制停止输出超过上限\n${result.combinedOutput}")
            result.exitCode != 0 -> TaskOutcome(
                StepStatus.FAILURE,
                "强制停止失败：退出码 ${result.exitCode}\n${result.combinedOutput}",
            )
            else -> TaskOutcome(
                StepStatus.SUCCESS,
                "已强制停止：${target.packageName}@${target.userId}",
            )
        }
    }

    internal suspend fun rootShell(
        root: RootInfo,
        command: String,
        run: suspend (String) -> ProcessResult = { RootSessions.run(root, it) },
    ): TaskOutcome {
        require(command.isNotBlank()) { "参数格式不正确：期望 非空 Shell 命令" }
        val result = try {
            run(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return TaskOutcome(
                StepStatus.FAILURE,
                "Shell 执行失败：${failure.message ?: failure.javaClass.simpleName}",
                mapOf("shell.stdout" to "", "shell.stderr" to "", "shell.code" to -1),
            )
        }
        val outputs = mapOf(
            "shell.stdout" to result.output,
            "shell.stderr" to result.stderr,
            "shell.code" to result.exitCode,
        )
        val streams = shellStreams(result)
        return when {
            result.timedOut -> TaskOutcome(
                StepStatus.FAILURE,
                "Shell 执行超时（30 秒）；已清理 Root 会话" + streams.withPrefix(),
                outputs,
            )
            result.truncated -> TaskOutcome(
                StepStatus.FAILURE,
                "Shell 输出超过上限，结果不完整" + streams.withPrefix(),
                outputs,
            )
            result.exitCode != 0 -> TaskOutcome(
                StepStatus.FAILURE,
                "Shell 退出码 ${result.exitCode}" + streams.withPrefix(),
                outputs,
            )
            else -> TaskOutcome(StepStatus.SUCCESS, streams.ifEmpty { "Shell 执行完成" }, outputs)
        }
    }

    private fun shellStreams(result: ProcessResult): String = buildString {
        append(result.output)
        if (result.stderr.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append("[标准错误]\n").append(result.stderr)
        }
    }

    private fun String.withPrefix(): String = if (isEmpty()) "" else "\n$this"
}
