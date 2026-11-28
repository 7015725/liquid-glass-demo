package com.flowglass.demo.system.exec

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.flowglass.demo.system.FlowAccessibilityService
import com.flowglass.demo.system.Root
import com.flowglass.demo.system.RootInfo
import com.flowglass.demo.system.clipboard.ClipboardArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Types into the currently focused editable field.
 *
 * Root is tried first when the selected execution layer provides it. Printable ASCII uses
 * `input text`; other text (Chinese, newlines, etc.) is placed on the clipboard and pasted with a
 * root key event, so Root input is no longer limited to ASCII. Accessibility remains the fallback
 * for unrooted devices and for inputs that reject paste. The text itself is never included in
 * failure/success details or trace parameters.
 */
internal object TextInputTask {
    /** True when the current accessibility window has an editable input focus. */
    fun hasFocusedEditable(): Boolean {
        val service = FlowAccessibilityService.instance ?: return false
        return runCatching {
            val root = service.rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: return false
            focused.isEditable
        }.getOrDefault(false)
    }

    /** True when either accessibility or a root shell can see an editable input focus. */
    suspend fun hasFocusedInput(root: RootInfo?): Boolean {
        if (hasFocusedEditable()) return true
        if (root == null) return false
        val probe = runCatching {
            RootSessions.run(root, "dumpsys input_method", timeoutMs = 5_000, outputLimit = 64_000)
        }.getOrNull() ?: return false
        if (probe.timedOut || probe.exitCode != 0) return false
        return probe.output.lineSequence().any { "mServedView=" in it && "EditText" in it }
    }

    suspend fun run(context: Context, text: String, root: RootInfo?): TaskOutcome {
        if (text.isEmpty()) return TaskOutcome(StepStatus.FAILURE, "输入文本为空")

        var rootFailure: String? = null
        if (root != null) {
            val outcome = injectWithRoot(context, text, root)
            if (outcome.status == StepStatus.SUCCESS) return outcome
            rootFailure = outcome.detail
        }

        val service = FlowAccessibilityService.instance
        var accessibilityFailure: String? = null
        if (service != null) {
            val outcome = withContext(Dispatchers.Main.immediate) {
                runCatching { injectWithAccessibility(service, text) }
                    .getOrElse { failure ->
                        TaskOutcome(
                            StepStatus.FAILURE,
                            "无障碍写入失败：${failure.message ?: failure.javaClass.simpleName}",
                        )
                    }
            }
            if (outcome.status == StepStatus.SUCCESS) return outcome
            accessibilityFailure = outcome.detail
        }

        return TaskOutcome(
            StepStatus.FAILURE,
            accessibilityFailure ?: rootFailure ?: "当前没有可写入的输入框；请开启无障碍服务",
        )
    }

    private suspend fun injectWithRoot(
        context: Context,
        text: String,
        root: RootInfo,
    ): TaskOutcome {
        val command = TextInputPlan.rootCommand(text)
        if (command != null) {
            return runRootCommand(root, command, text.length)
        }

        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return TaskOutcome(StepStatus.FAILURE, "剪贴板服务不可用")
        val setClipboard = withContext(Dispatchers.Main.immediate) {
            runCatching {
                ClipboardArchive.suppressNext(text)
                manager.setPrimaryClip(ClipData.newPlainText("流光输入", text))
            }.isSuccess
        }
        if (!setClipboard) {
            return TaskOutcome(StepStatus.FAILURE, "Root 输入准备失败：无法写入剪贴板")
        }
        return runRootCommand(root, "input keyevent 279", text.length)
    }

    private suspend fun runRootCommand(
        root: RootInfo,
        command: String,
        length: Int,
    ): TaskOutcome {
        var persistentFailure: String? = null
        val persistent = try {
            RootSessions.run(root, command, timeoutMs = 5_000)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            persistentFailure = "Root 会话失败：${failure.message ?: failure.javaClass.simpleName}"
            null
        }
        persistent?.let { result ->
            when {
                result.timedOut -> persistentFailure = "Root 会话超时"
                result.truncated -> persistentFailure = "Root 会话输出超过上限"
                result.exitCode != 0 -> persistentFailure = "Root 会话退出码 ${result.exitCode}"
                else -> return TaskOutcome(StepStatus.SUCCESS, "已输入 $length 个字符（Root）")
            }
        }

        // Some newer ROMs/root managers cannot run the persistent supervisor shell even
        // though a one-shot `su -c` works. Fall back to the same execution path as the
        // manual command before reporting a failure.
        val oneShot = try {
            Root.runProcess(
                listOf(root.suPath, "-c", command),
                timeoutMs = 10_000,
                outputLimit = 1_000,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return TaskOutcome(
                StepStatus.FAILURE,
                "Root 输入失败：${failure.message ?: failure.javaClass.simpleName}",
            )
        }
        return when {
            oneShot.timedOut -> TaskOutcome(StepStatus.FAILURE, "Root 输入超时")
            oneShot.truncated -> TaskOutcome(StepStatus.FAILURE, "Root 输入输出超过上限")
            oneShot.exitCode != 0 -> TaskOutcome(
                StepStatus.FAILURE,
                "Root 输入失败：退出码 ${oneShot.exitCode}${persistentFailure?.let { "（$it）" } ?: ""}",
            )
            else -> TaskOutcome(StepStatus.SUCCESS, "已输入 $length 个字符（Root）")
        }
    }

    private fun injectWithAccessibility(
        service: AccessibilityService,
        text: String,
    ): TaskOutcome {
        val root = service.rootInActiveWindow
            ?: return TaskOutcome(StepStatus.FAILURE, "无法读取当前界面；请确认无障碍服务可用")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return TaskOutcome(StepStatus.FAILURE, "当前没有聚焦的输入框")
        if (!focused.isEditable) {
            return TaskOutcome(StepStatus.FAILURE, "当前焦点不是可编辑的输入框")
        }

        val hint = focused.hintText?.toString()
        val rawText = focused.text?.toString().orEmpty()
        val showingHint = focused.isShowingHintText || (hint != null && rawText == hint)
        val existing = if (showingHint) "" else rawText
        val selectionStart = if (showingHint) 0 else focused.textSelectionStart
        val selectionEnd = if (showingHint) 0 else focused.textSelectionEnd
        val next = TextInputPlan.insert(
            existing = existing,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            text = text,
        )
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return TaskOutcome(StepStatus.FAILURE, "当前输入框不支持无障碍写入")
        }
        val target = focused.packageName?.toString()?.takeIf { it.isNotBlank() }
        return TaskOutcome(
            StepStatus.SUCCESS,
            if (target == null) "已输入 ${text.length} 个字符"
            else "已输入 ${text.length} 个字符（$target）",
        )
    }
}
