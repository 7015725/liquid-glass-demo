package com.flowglass.demo.system.exec

import com.flowglass.engine.BuiltInActions
import com.flowglass.engine.ParameterCodec
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskParametersNotificationTest {
    private val notificationMeta = BuiltInActions.metadata.single { it.typeId == "action.post_notification" }
    private val toastMeta = BuiltInActions.metadata.single { it.typeId == "action.toast" }

    @Test
    fun legacyNotificationTextIsSystemShort() {
        assertEquals(
            NotificationSpec("标题", "正文", "system", "short"),
            TaskParameters.notification("标题|正文"),
        )
    }

    @Test
    fun structuredNotificationModeAndDurationAreRead() {
        val text = ParameterCodec.formatComponent(notificationMeta, mapOf(
            "title" to JsonPrimitive("标题"),
            "content" to JsonPrimitive("正文"),
            "mode" to JsonPrimitive("both"),
            "toastDuration" to JsonPrimitive("long"),
        ))
        assertEquals(NotificationSpec("标题", "正文", "both", "long"), TaskParameters.notification(text))
    }

    @Test
    fun legacyToastAndStructuredLongToastAreRead() {
        assertEquals(ToastSpec("提示", "short"), TaskParameters.toast("提示"))
        val text = ParameterCodec.formatComponent(toastMeta, mapOf(
            "text" to JsonPrimitive("提示"),
            "duration" to JsonPrimitive("long"),
        ))
        assertEquals(ToastSpec("提示", "long"), TaskParameters.toast(text))
    }

    @Test
    fun toastTextUsesTitleAndContentWithEmptyFallbacks() {
        assertEquals("正文", NotificationSpec("", "正文", "toast", "short").toastText)
        assertEquals("标题", NotificationSpec("标题", "", "toast", "short").toastText)
        assertEquals("标题 · 正文", NotificationSpec("标题", "正文", "toast", "short").toastText)
    }
}
