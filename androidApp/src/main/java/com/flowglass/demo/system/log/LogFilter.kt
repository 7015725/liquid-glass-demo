package com.flowglass.demo.system.log

import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceLevel

data class LogFilter(
    val minLevel: TraceLevel = TraceLevel.INFO,
    val kinds: Set<TraceKind> = emptySet(),
    val search: String = "",
    val beforeMs: Long? = null,
    val limit: Int = 500,
)
