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
    WEEKDAYS,
    APPLICATION,
    ENUM,
}

/**
 * Presentation-only editor choice for a parameter. [AUTO] resolves from [ParamType], so new
 * components automatically get the standard control for their storage type. Overrides are for
 * values stored as TEXT that still need a dedicated picker (for example a rule name).
 *
 * This is intentionally separate from [ParamType]: changing how a value is edited must not
 * change its persisted JSON shape or its validation rules.
 */
@Serializable
enum class ParamEditor {
    AUTO,
    TEXT,
    MULTILINE,
    STEPPER,
    SLIDER,
    SEGMENTED,
    TIME,
    TIME_RANGE,
    INT_RANGE,
    WEEKDAYS,
    DURATION,
    APPLICATION,
    RULE,
    LOCATION,
}

/** Dynamic data source for an editor such as APPLICATION or RULE. */
@Serializable
enum class ParamSource {
    NONE,
    LAUNCHER_APPS,
    RULES,
    CURRENT_LOCATION,
}

/** Resolves the presentation control for a spec without changing its storage type. */
fun ParamSpec.resolvedEditor(): ParamEditor = when (editor) {
    ParamEditor.AUTO -> when (type) {
        ParamType.TEXT -> ParamEditor.TEXT
        ParamType.MULTILINE_TEXT -> ParamEditor.MULTILINE
        ParamType.INTEGER -> ParamEditor.STEPPER
        ParamType.PERCENTAGE -> ParamEditor.SLIDER
        ParamType.DURATION -> ParamEditor.DURATION
        ParamType.TRI_STATE, ParamType.ENUM -> ParamEditor.SEGMENTED
        ParamType.TIME -> ParamEditor.TIME
        ParamType.TIME_RANGE -> ParamEditor.TIME_RANGE
        ParamType.INT_RANGE -> ParamEditor.INT_RANGE
        ParamType.WEEKDAYS -> ParamEditor.WEEKDAYS
        ParamType.APPLICATION -> ParamEditor.APPLICATION
    }
    else -> editor
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
    /** Presentation control; [ParamEditor.AUTO] resolves from [type]. */
    val editor: ParamEditor = ParamEditor.AUTO,
    /** Dynamic source for editors such as [ParamEditor.APPLICATION] or [ParamEditor.RULE]. */
    val source: ParamSource = ParamSource.NONE,
)

/** Task-chain semantics executed by the interpreter instead of a plain action handler. */
@Serializable
enum class BlockKind {
    IF,
    LOOP,
    WAIT,
    BREAK,
    STOP,
    SET_VAR,
    RUN_RULE,
    ;

    /** Blocks that own a nested sequence and therefore indent in the editor. */
    val container: Boolean get() = this == IF || this == LOOP
}

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
    /** Visible in the add panel but not selectable until a later stage lands. */
    val comingSoon: Boolean = false,
    /** Non-null for if/loop/wait/break/stop/set_var/run_rule; plain actions leave it null. */
    val block: BlockKind? = null,
)

@Serializable
data class RuleBranch(
    val conditions: List<RuleComponent> = emptyList(),
    val children: List<RuleComponent> = emptyList(),
)

@Serializable
class RuleComponent(
    val type: String,
    val params: Map<String, JsonElement> = emptyMap(),
    val onError: OnError = OnError.STOP,
    /** v3: if 的 then、loop 的循环体；普通步骤为空。 */
    val children: List<RuleComponent> = emptyList(),
    /** v3: if 的 else-if / else；最后一项 conditions 为空表示 else。 */
    val branches: List<RuleBranch> = emptyList(),
    /** v3: if 的分支条件、while 循环条件、wait_until 条件。 */
    val conditions: List<RuleComponent> = emptyList(),
    /** Stable UI identity; the engine never interprets it and it is excluded from equality. */
    val id: Long? = null,
) {
    fun copy(
        type: String = this.type,
        params: Map<String, JsonElement> = this.params,
        onError: OnError = this.onError,
        children: List<RuleComponent> = this.children,
        branches: List<RuleBranch> = this.branches,
        conditions: List<RuleComponent> = this.conditions,
        id: Long? = this.id,
    ): RuleComponent = RuleComponent(type, params, onError, children, branches, conditions, id)

    // UI ids must not make two semantically identical rules compare unequal.
    override fun equals(other: Any?): Boolean = other is RuleComponent &&
        type == other.type && params == other.params && onError == other.onError &&
        children == other.children && branches == other.branches && conditions == other.conditions

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + params.hashCode()
        result = 31 * result + onError.hashCode()
        result = 31 * result + children.hashCode()
        result = 31 * result + branches.hashCode()
        result = 31 * result + conditions.hashCode()
        return result
    }

    override fun toString(): String =
        "RuleComponent(type=$type, params=$params, onError=$onError, children=$children, " +
            "branches=$branches, conditions=$conditions, id=$id)"
}

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
