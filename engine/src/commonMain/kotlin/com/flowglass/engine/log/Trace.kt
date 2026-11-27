package com.flowglass.engine.log

import com.flowglass.engine.StepStatus
import com.flowglass.engine.newIdString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun newTraceId(): String = newIdString()

/** ERROR is the least verbose; TRACE is the most verbose. */
@Serializable
enum class TraceLevel { ERROR, WARN, INFO, DEBUG, TRACE }

@Serializable
enum class TraceKind {
    EVENT, MATCH, CONDITION, RUN, ACTION, VARIABLE, QUEUE, SOURCE, STORAGE, SYSTEM, CONFIG,
}

@Serializable
enum class TraceStatus {
    STARTED, SUCCESS, FAILURE, SKIPPED, DROPPED, QUEUED, DEBOUNCED, CANCELLED, INFO,
}

@Serializable
data class TraceEvent(
    val id: String = newTraceId(),
    val traceId: String,
    val spanId: String = newTraceId(),
    val parentSpanId: String? = null,
    val runId: String? = null,
    val atEpochMs: Long,
    val kind: TraceKind,
    val status: TraceStatus,
    val level: TraceLevel = TraceLevel.INFO,
    val ruleId: String? = null,
    val cmdId: Long? = null,
    val ruleName: String? = null,
    val source: String? = null,
    val eventKind: String? = null,
    val componentTypeId: String? = null,
    val componentPath: List<Int> = emptyList(),
    val message: String = "",
    val detail: String = "",
    val durationMs: Long? = null,
    val params: Map<String, String> = emptyMap(),
    val eventData: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val errorStack: String? = null,
)

fun interface TraceSink {
    fun record(event: TraceEvent)
}

object NoopTraceSink : TraceSink {
    override fun record(event: TraceEvent) = Unit
}

/** Shared JSON codec for the Android store and engine tests. */
object TraceJson {
    val format = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }
}

fun TraceLevel.allows(configured: TraceLevel): Boolean = ordinal <= configured.ordinal

fun StepStatus.toTraceStatus(): TraceStatus = when (this) {
    StepStatus.SUCCESS -> TraceStatus.SUCCESS
    StepStatus.FAILURE -> TraceStatus.FAILURE
    StepStatus.SKIPPED -> TraceStatus.SKIPPED
}
