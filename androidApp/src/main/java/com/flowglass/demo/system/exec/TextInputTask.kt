package com.flowglass.demo.system.exec

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.flowglass.demo.system.FlowAccessibilityService
import com.flowglass.demo.system.RootInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Types into the currently focused editable field.
 *
 * Accessibility is the primary path because it can deliver arbitrary Unicode text directly and
 * inserts at the caret instead of replacing the field. The Root fallback uses `input text`, which
 * is limited to printable ASCII; it is useful on rooted devices where accessibility is disabled.
 * The text itself is never included in failure/success details or trace parameters.
 */
internal object TextInputTask {
    suspend fun run(text: String, root: RootInfo?): TaskOutcome {
        if (text.isEmpty()) return TaskOutcome(StepStatus.FAILURE, "输入文本为空")

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

        if (root == null) {
            return TaskOutcome(
                StepStatus.FAILURE,
                accessibilityFailure ?: "当前没有可写入的输入框；请开启无障碍服务",
            )
        }
        val command = TextInputPlan.rootCommand(text)
            ?: return TaskOutcome(
                StepStatus.FAILURE,
                "Root 键入仅支持 ASCII；中文等字符请开启无障碍服务",
            )

        val result = RootSessions.run(root, command, timeoutMs = 10_000)
        return when {
            result.timedOut -> TaskOutcome(StepStatus.FAILURE, "Root 文本输入超时")
            result.truncated -> TaskOutcome(StepStatus.FAILURE, "Root 文本输入输出超过上限")
            result.exitCode != 0 -> TaskOutcome(
                StepStatus.FAILURE,
                "Root 文本输入失败：退出码 ${result.exitCode}${result.combinedOutput.take(200).let { if (it.isBlank()) "" else "\n$it" }}",
            )
            else -> TaskOutcome(StepStatus.SUCCESS, "已输入 ${text.length} 个字符（Root）")
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
