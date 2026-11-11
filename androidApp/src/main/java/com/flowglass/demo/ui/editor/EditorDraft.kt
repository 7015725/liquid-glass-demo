package com.flowglass.demo.ui.editor

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only a complete, formally encodable Rule may enter saveable model state.
 * Raw/invalid edits live in EditorInputs or their open dialog, never in this model.
 * The encoded snapshot is committed with the model, so restoration cannot discover
 * an encoding failure after the UI has already accepted an update.
 */
@Stable
internal class EditorDraft private constructor(rule: Rule, encoded: String) {
    constructor(rule: Rule) : this(rule, encode(rule))

    private var accepted by mutableStateOf(rule to encoded)
    val rule: Rule get() = accepted.first

    /** Throws before assignment; the editing boundary must display the rejection. */
    fun update(candidate: Rule) {
        val encoded = encode(candidate)
        accepted = candidate to encoded
    }

    companion object {
        private val compact = Json(RuleCodec.json) { prettyPrint = false }
        fun encode(rule: Rule): String {
            // Enforce the official pretty-printed export budget, but do not duplicate
            // its whitespace in Android's limited saved-state Bundle for open dialogs.
            RuleCodec.encodeRule(rule)
            return compact.encodeToString(rule)
        }
        val saver = Saver<EditorDraft, String>(
            save = { it.accepted.second },
            restore = { EditorDraft(RuleCodec.decodeRule(it)) })
    }
}

@Composable
internal fun rememberEditorDraft(initial: Rule): EditorDraft =
    rememberSaveable(initial.id, saver = EditorDraft.saver) { EditorDraft(initial) }
