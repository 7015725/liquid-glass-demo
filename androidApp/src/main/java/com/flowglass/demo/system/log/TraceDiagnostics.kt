package com.flowglass.demo.system.log

import com.flowglass.engine.log.TraceEvent
import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceLevel
import com.flowglass.engine.log.TraceSink
import com.flowglass.engine.log.TraceStatus
import com.flowglass.engine.log.newTraceId

/** Emits framework-level traces that do not belong to a rule run. */
internal fun TraceSink.recordDiagnostic(
    kind: TraceKind,
    status: TraceStatus,
    message: String,
    detail: String = "",
    source: String? = null,
    level: TraceLevel = TraceLevel.INFO,
    error: Throwable? = null,
) {
    record(
        TraceEvent(
            traceId = newTraceId(),
            atEpochMs = System.currentTimeMillis(),
            kind = kind,
            status = status,
            level = level,
            source = source,
            message = message,
            detail = detail,
            errorCode = error?.let { it::class.simpleName },
            errorStack = error?.stackTraceToString(),
        ),
    )
}
