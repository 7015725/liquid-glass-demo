package com.flowglass.engine

import kotlinx.serialization.Serializable

/** Which table a user-visible variable belongs to. */
enum class VariableScope { BUILTIN, OUTPUT, LOCAL, GLOBAL }

/** The six storage/editor types. Values are converted at every boundary. */
@Serializable
enum class VariableType {
    INT,
    BOOLEAN,
    TEXT,
    INT_LIST,
    BOOLEAN_LIST,
    TEXT_LIST;

    val storageKey: String get() = name.lowercase()

    val isList: Boolean get() = this == INT_LIST || this == BOOLEAN_LIST || this == TEXT_LIST

    companion object {
        fun fromStorage(raw: String?): VariableType? =
            values().firstOrNull { it.storageKey == raw?.trim()?.lowercase() }
    }
}

/** One built-in or event variable described for insertion and the management page. */
data class VariableSpec(
    val name: String,
    val scope: VariableScope,
    val type: VariableType,
    val description: String,
    /** Legacy `%{...}` key this built-in replaces, when there is one. */
    val legacyKey: String? = null,
    /** Trigger kind that produces this event variable; empty for run/action built-ins. */
    val source: String = "",
)

/** A variable created or type-changed during a run; the host persists its metadata. */
data class VariableDefinition(
    val name: String,
    val scope: VariableScope,
    val type: VariableType,
    val resetEachRun: Boolean = false,
    val initial: Any? = null,
)

/**
 * Naming rules for user-defined variables. These are deliberately independent from the legacy
 * `%{...}` scanner so old names keep working while the new UI only offers `{名字}`.
 */
object VariableName {
    const val MAX_LENGTH = 64

    fun normalize(raw: String): String = raw.trim()

    fun isValid(raw: String): Boolean {
        val name = normalize(raw)
        if (name.length !in 1..MAX_LENGTH) return false
        val first = name[0]
        if (!first.isLetter() && first != '_') return false
        for (char in name) {
            if (!char.isLetterOrDigit() && char != '_' && char != '-') return false
        }
        return true
    }

    fun error(raw: String): String? = when {
        normalize(raw).isEmpty() -> "名字不能为空"
        !isValid(raw) -> "名字只能用中文、字母、数字、_ 或 -，不能有空格和点，长度 1–64"
        else -> null
    }

    fun token(name: String): String = "{$name}"
}

/**
 * Built-in variable catalogue. After the output-variable migration only true system values
 * remain here; trigger events and action results are component outputs with instance names.
 */
object BuiltInVariables {
    val all: List<VariableSpec> = listOf(
        VariableSpec("上一步结果", VariableScope.BUILTIN, VariableType.TEXT,
            "上一步执行的结果摘要", legacyKey = "last"),
    )

    val byName: Map<String, VariableSpec> = all.associateBy { it.name }
    val byLegacyKey: Map<String, VariableSpec> = all.mapNotNull { spec -> spec.legacyKey?.let { it to spec } }.toMap()

    fun specsForSource(source: String): List<VariableSpec> = all.filter { it.source == source }
}
