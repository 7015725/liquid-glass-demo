package com.flowglass.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.random.Random

internal fun newIdString(): String {
    val bytes = Random.Default.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

@Serializable
enum class RuleType {
    @SerialName("auto") AUTO,
    @SerialName("manual") MANUAL,
}

@Serializable
enum class RunMode {
    @SerialName("drop") DROP,
    @SerialName("queue") QUEUE,
    @SerialName("restart") RESTART,
    @SerialName("parallel") PARALLEL,
}

@Serializable
enum class MatchMode {
    @SerialName("all") ALL,
    @SerialName("any") ANY,
    @SerialName("custom") CUSTOM,
}

@Serializable
enum class OnError {
    @SerialName("stop") STOP,
    @SerialName("continue") CONTINUE,
    @SerialName("retry") RETRY,
}

@Serializable
data class MatchSpec(
    val mode: MatchMode = MatchMode.ANY,
    val windowSeconds: Int = 60,
    val atLeast: Int? = null,
    val tree: MatchNode? = null,
)

@Serializable
data class MatchNode(
    val op: String = "leaf",
    val ref: String? = null,
    val children: List<MatchNode> = emptyList(),
)

@Serializable
data class Rule(
    val id: String,
    val name: String = "",
    val description: String = "",
    val type: RuleType = RuleType.AUTO,
    val enabled: Boolean = false,
    val runMode: RunMode = RunMode.DROP,
    val triggerMatch: MatchSpec = MatchSpec(),
    val triggers: List<RuleComponent> = emptyList(),
    val conditionMatch: MatchSpec = MatchSpec(),
    val conditions: List<RuleComponent> = emptyList(),
    val actions: List<RuleComponent> = emptyList(),
    val locals: List<LocalVariable> = emptyList(),
)

@Serializable
data class LocalVariable(
    val name: String,
    val type: String,
    val initial: kotlinx.serialization.json.JsonElement,
    val resetEachRun: Boolean = false,
)

@Serializable
data class RulePresentation(
    val category: Int = 0,
    val icon: String = "Bolt",
    val pinned: Boolean = false,
    val lastRun: String = "从未",
    val descriptionEdited: Boolean = true,
    val legacyId: Long? = null,
    val triggers: List<ComponentPresentation> = emptyList(),
    val conditions: List<ComponentPresentation> = emptyList(),
    val actions: List<ComponentPresentation> = emptyList(),
)

@Serializable
data class RuleDocument(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val rules: List<Rule> = emptyList(),
    val ui: RuleUiMetadata = RuleUiMetadata(),
)

@Serializable
data class RuleUiMetadata(
    val running: Boolean = true,
    val mode: Int = 1,
    val logs: List<PersistedLog> = emptyList(),
    val presentation: Map<String, RulePresentation> = emptyMap(),
)

@Serializable
data class PersistedLog(
    val id: Long,
    val title: String,
    val reason: String,
    val time: String,
    val ok: Boolean,
    val icon: String,
    val detail: String = "",
    val steps: List<PersistedLogStep> = emptyList(),
)

@Serializable
data class PersistedLogStep(
    val itemId: Long,
    val name: String,
    val status: String,
    val detail: String,
    val durationMs: Long,
)

object RuleFactory {
    fun create(type: RuleType, name: String = ""): Rule {
        return Rule(id = newIdString(), type = type, name = name)
    }
}

object RuleJson {
    val format = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(document: RuleDocument): String {
        require(document.formatVersion == CURRENT_FORMAT_VERSION) { "不支持的格式版本" }
        ParameterCodec.validateDocument(document)
        return format.encodeToString(document)
    }

    fun decode(text: String): RuleDocument {
        val document = format.decodeFromString<RuleDocument>(text)
        require(document.formatVersion == CURRENT_FORMAT_VERSION) { "不支持的格式版本 ${document.formatVersion}" }
        ParameterCodec.validateDocument(document)
        return document
    }
}

const val CURRENT_FORMAT_VERSION = 2
