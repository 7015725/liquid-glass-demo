package com.flowglass.engine.methodhook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The signed unit on the target-process -> App Binder channel. [body] is the exact UTF-8 JSON that
 * was HMAC'd; it is decoded only after the MAC succeeds, so a malformed body from an attacker does
 * not reach the JSON parser first.
 */
@Serializable
data class MethodHookWirePacket(
    /** Unsigned routing hint; it is only used to select the token before verifying the MAC. */
    val hookId: String,
    val body: String,
    val mac: String,
)

@Serializable
data class MethodHookWireEnvelope(
    val hookId: String,
    val packageName: String,
    val userId: Int,
    val processName: String,
    val session: String,
    val seq: Long,
    val timestamp: Long,
    /** hello, install or event */
    val type: String,
    val variables: Map<String, String> = emptyMap(),
    val status: String? = null,
    val detail: String? = null,
) {
    companion object {
        const val TYPE_HELLO = "hello"
        const val TYPE_INSTALL = "install"
        const val TYPE_EVENT = "event"
    }
}

object MethodHookWireJson {
    private val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeEnvelope(envelope: MethodHookWireEnvelope): String =
        format.encodeToString(MethodHookWireEnvelope.serializer(), envelope)

    fun decodeEnvelope(body: String): MethodHookWireEnvelope =
        format.decodeFromString(MethodHookWireEnvelope.serializer(), body)

    fun encodePacket(packet: MethodHookWirePacket): String =
        format.encodeToString(MethodHookWirePacket.serializer(), packet)

    fun decodePacket(text: String): MethodHookWirePacket =
        format.decodeFromString(MethodHookWirePacket.serializer(), text)
}

object MethodHookStatus {
    const val INSTALLED = "installed"
    const val NOT_FOUND = "not_found"
    const val AMBIGUOUS = "ambiguous"
    const val FAILED = "failed"
    const val BLOCKED = "blocked"
    const val RATE_LIMITED = "rate_limited"
}
