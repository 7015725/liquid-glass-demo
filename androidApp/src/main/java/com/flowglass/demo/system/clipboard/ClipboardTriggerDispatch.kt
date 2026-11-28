package com.flowglass.demo.system.clipboard

import android.content.Context
import com.flowglass.demo.ui.AppRuntime
import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceLevel
import com.flowglass.engine.log.TraceStatus
import com.flowglass.demo.system.log.recordDiagnostic
import com.flowglass.demo.system.trigger.TriggerService
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ClipboardTriggerDispatch {
    fun deliver(context: Context, payload: ClipboardPayload) {
        val event = TriggerEvent(
            kind = TriggerKinds.CLIPBOARD_CHANGED,
            source = TriggerSource.CLIPBOARD,
            atEpochMs = payload.atEpochMs,
            data = mapOf(
                "clipboard.text" to payload.content,
                "clipboard.package" to payload.packageName,
                "clipboard.user" to payload.userId.toString(),
                "clipboard.time" to SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault(),
                ).format(Date(payload.atEpochMs)),
                "clipboard.mime" to payload.mimeType,
            ),
        )
        if (TriggerService.deliverIfRunning(event)) return
        runCatching {
            AppRuntime.of(context).logStore.recordDiagnostic(
                kind = TraceKind.SOURCE,
                status = TraceStatus.SKIPPED,
                source = "CLIPBOARD",
                message = "剪贴板事件已记录，但触发器服务未运行",
                detail = "source=FOREGROUND",
                level = TraceLevel.DEBUG,
            )
        }
    }
}
