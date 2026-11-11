package com.flowglass.demo.ui.editor

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.flowglass.engine.Component
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleCodec
import kotlinx.serialization.json.*
import java.util.UUID

internal const val EDITOR_MAX_DEPTH = 6
internal const val EDITOR_MAX_COMPONENTS = 100

/** Null means invalid input. The caller keeps the original text, never a fallback value. */
internal fun parseEditorValue(kind: String, text: String): JsonElement? = when (kind) {
    "int32" -> if (Regex("-?(0|[1-9][0-9]*)").matches(text)) text.toIntOrNull()?.let(::JsonPrimitive) else null
    "long", "int" -> if (Regex("-?(0|[1-9][0-9]*)").matches(text)) text.toLongOrNull()?.let(::JsonPrimitive) else null
    "double", "decimal" -> text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
    "bool" -> text.toBooleanStrictOrNull()?.let(::JsonPrimitive)
    "json", "list", "dict", "null" -> try {
        RuleCodec.decodeJsonValue(text).also { value ->
            // Early single-value feedback only; EditorDraft checks every complete candidate.
            RuleCodec.encodeRule(Rule(actions = listOf(Component(type = "action.variable_set", params = mapOf("value" to value)))))
        }
    } catch (_: IllegalArgumentException) { null }
    else -> JsonPrimitive(text)
}

internal fun editorValueText(value: JsonElement, kind: String): String =
    if (kind in setOf("text", "time", "code", "choice", "string") && value is JsonPrimitive && value.isString) value.content else value.toString()

internal fun copyEditorComponent(c: Component): Component = c.copy(
    id = UUID.randomUUID().toString(), children = c.children.map(::copyEditorComponent), elseActions = c.elseActions.map(::copyEditorComponent))

internal fun componentCount(list: List<Component>): Int = list.sumOf { 1 + componentCount(it.children) + componentCount(it.elseActions) }
internal fun componentDepth(c: Component): Int = 1 + (c.children + c.elseActions).maxOfOrNull(::componentDepth).orZero()
private fun Int?.orZero() = this ?: 0

/** Raw text and errors survive tab switches and activity recreation, including invalid numbers. */
@Stable
internal class EditorInputs(values: Map<String, String> = emptyMap(), errors: Map<String, String> = emptyMap()) {
    var values by mutableStateOf(values)
        private set
    var errors by mutableStateOf(errors)
        private set
    fun value(key: String, fallback: String) = values[key] ?: fallback
    fun edit(key: String, text: String, kind: String, accept: (JsonElement) -> Unit) {
        values = values + (key to text)
        val parsed = parseEditorValue(kind, text)
        if (parsed == null) errors = errors + (key to "请输入合法的 $kind 值，不能溢出、使用非有限数值或超过 JSON 大小、节点或深度上限")
        else attempt(key) { accept(parsed) }
    }
    /** Rejections keep the last model and the raw input; never acknowledge a failed edit. */
    fun attempt(key: String, accept: () -> Unit) {
        try { accept(); errors = errors - key }
        catch (failure: IllegalArgumentException) {
            errors = errors + (key to (failure.message ?: "草稿超过可保存上限，请修改输入"))
        }
    }
    fun clear(prefix: String) {
        values = values.filterKeys { !it.startsWith(prefix) }
        errors = errors.filterKeys { !it.startsWith(prefix) }
    }
    fun errorText() = errors.entries.joinToString("\n") { "${it.key}：${it.value}" }
    companion object {
        val saver = Saver<EditorInputs, List<String>>(
            save = { listOf(JsonObject(it.values.mapValues { JsonPrimitive(it.value) }).toString(), JsonObject(it.errors.mapValues { JsonPrimitive(it.value) }).toString()) },
            restore = { data ->
                fun decode(s: String) = RuleCodec.json.parseToJsonElement(s).jsonObject.mapValues { it.value.jsonPrimitive.content }
                EditorInputs(decode(data[0]), decode(data[1]))
            })
    }
}

@Composable
internal fun rememberEditorInputs(key: String): EditorInputs = rememberSaveable(key, saver = EditorInputs.saver) { EditorInputs() }
