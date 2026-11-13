package com.flowglass.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class Tier { NORMAL, SHIZUKU, ROOT, XPOSED }

@Serializable
enum class ComponentKind { TRIGGER, CONDITION, ACTION }

@Serializable
enum class ParamType {
    TEXT,
    MULTILINE_TEXT,
    INTEGER,
    PERCENTAGE,
    DURATION,
    TRI_STATE,
    TIME,
    TIME_RANGE,
    INT_RANGE,
    APPLICATION,
    ENUM,
}

@Serializable
data class ParamOption(val value: String, val label: String)

@Serializable
data class ParamSpec(
    val key: String,
    val label: String,
    val type: ParamType = ParamType.TEXT,
    val required: Boolean = true,
    val defaultValue: JsonElement = JsonNull,
    val options: List<ParamOption> = emptyList(),
    val hint: String = "",
    val minValue: Long? = null,
    val maxValue: Long? = null,
    val suffix: String = "",
)

@Serializable
data class ComponentMeta(
    val typeId: String,
    val name: String,
    val category: String,
    val kind: ComponentKind,
    val params: List<ParamSpec> = emptyList(),
    val permissions: List<String> = emptyList(),
    val minTier: Tier = Tier.NORMAL,
    val outputs: List<String> = emptyList(),
    val description: String = "",
)

@Serializable
data class RuleComponent(
    val type: String,
    val params: Map<String, JsonElement> = emptyMap(),
    val onError: OnError = OnError.STOP,
)

@Serializable
data class ComponentPresentation(
    val id: Long,
    val icon: String,
)

fun textParam(
    key: String,
    label: String,
    defaultValue: String = "",
    required: Boolean = true,
    hint: String = "",
) = ParamSpec(key, label, ParamType.TEXT, required, JsonPrimitive(defaultValue), hint = hint)
