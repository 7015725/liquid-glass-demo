package com.flowglass.demo.ui

import com.flowglass.engine.BuiltInComponents
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationDisplayTest {
    private val meta = requireNotNull(BuiltInComponents.registry().find("action.post_notification"))

    @Test fun emptyTitleUsesOnlyTheContent() {
        val item = CommandItem(1, "发送通知", "|%{last}", Ic.Bell, "action.post_notification")
        assertEquals("发送通知：%{last}", item.display)
        assertEquals("%{last}", notificationDisplayText(item.parameter))
    }

    @Test fun nonEmptyTitleUsesTheDotSeparatorAndKeepsBodySeparators() {
        val item = CommandItem(2, "发送通知", "每日报告|%{last}|保留", Ic.Bell, "action.post_notification")
        assertEquals("发送通知：每日报告 · %{last}|保留", item.display)
    }

    @Test fun notificationFieldsKeepTheVariableInContentAndRoundTrip() {
        val fields = ParameterForm.fields(meta, "|%{last}")
        assertEquals("", fields["title"])
        assertEquals("%{last}", fields["content"])
        assertEquals("|%{last}", ParameterForm.compose(meta, fields).getOrThrow())
    }

    @Test fun aCommandDescriptionUsesTheDisplayForm() {
        val command = Cmd(
            id = 3, auto = false, ic = Ic.Bell, title = "通知测试", desc = "",
            actions = listOf(
                CommandItem(4, "复制到剪贴板", "已复制", Ic.Copy, "action.copy_clipboard"),
                CommandItem(5, "发送通知", "|%{last}", Ic.Bell, "action.post_notification"),
            ),
        )
        assertEquals(
            "复制到剪贴板：已复制 → 发送通知：%{last}",
            command.actions.joinToString(" → "),
        )
    }
}
