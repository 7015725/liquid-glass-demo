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

/**
 * How a `%{var}` inside this parameter is substituted.
 *
 * The executor never special-cases component ids: each [ParamSpec] declares the context its
 * value is used in, and [ParamTemplate] applies the matching escaping rule.
 */
@Serializable
enum class ParamExpansion {
    /** Plain text: substitute the value verbatim (notification body, clipboard text, ...). */
    PLAIN,

    /** POSIX shell: values become generated `FG_Vn='...'` assignments; the command only sees references. */
    SHELL_QUOTED,

    /** URL component: percent-encode the substituted value as UTF-8. */
    URL_ENCODED,

    /**
     * `GET <url>` / `POST <url> <body>`: encode substitutions in the URL segment only.
     * The body keeps plain-text semantics.
     */
    HTTP_REQUEST,
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
    /** Context in which `%{var}` values are substituted; see [ParamExpansion]. */
    val expansion: ParamExpansion = ParamExpansion.PLAIN,
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
