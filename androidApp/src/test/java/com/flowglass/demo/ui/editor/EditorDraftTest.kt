package com.flowglass.demo.ui.editor

import com.flowglass.engine.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EditorDraftTest {
    @Test fun rejectedUpdateKeepsPreviousRuleAndCanBeCorrected() {
        val initial = Rule(name = "before")
        val draft = EditorDraft(initial)
        val invalid = initial.copy(description = "x".repeat(EngineLimits.MAX_BYTES))
        assertThrows(IllegalArgumentException::class.java) { draft.update(invalid) }
        assertEquals(initial, draft.rule)
        draft.update(initial.copy(name = "after"))
        assertEquals("after", draft.rule.name)
    }

    @Test fun structuralLimitsAreCheckedBeforeAComponentEntersTheModel() {
        val initial = Rule()
        val draft = EditorDraft(initial)
        var component = Component(type = "action.stop")
        repeat(EngineLimits.MAX_DEPTH) { component = Component(type = "action.loop", children = listOf(component)) }
        assertThrows(IllegalArgumentException::class.java) { draft.update(initial.copy(actions = listOf(component))) }
        assertEquals(initial, draft.rule)
        assertThrows(IllegalArgumentException::class.java) { EditorDraft(initial.copy(actions = listOf(component))) }
    }

    @Test fun restoredCompactSnapshotMustStillMeetTheOfficialEncodingBudget() {
        var value: JsonElement = JsonArray(List(2_100) { JsonPrimitive(0) })
        repeat(EngineLimits.MAX_VALUE_DEPTH - 2) { value = JsonArray(listOf(value)) }
        val actions = List(2) { Component(type = "action.variable_set", params = mapOf("value" to value)) }
        val oversized = Rule(actions = actions)
        val compact = Json(RuleCodec.json) { prettyPrint = false }.encodeToString(oversized)
        assertTrue(compact.toByteArray(Charsets.UTF_8).size < EngineLimits.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.encodeRule(oversized) }
        assertThrows(IllegalArgumentException::class.java) { EditorDraft.saver.restore(compact) }
    }
}
