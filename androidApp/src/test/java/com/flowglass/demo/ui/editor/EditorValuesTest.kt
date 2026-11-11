package com.flowglass.demo.ui.editor

import com.flowglass.engine.Component
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class EditorValuesTest {
    @Test fun invalidNumericTextIsNotDefaulted() {
        assertNull(parseEditorValue("long", "1.5"))
        assertNull(parseEditorValue("long", "9223372036854775808"))
        assertNull(parseEditorValue("double", "NaN"))
        assertNull(parseEditorValue("json", "{"))
        assertEquals(JsonPrimitive(Long.MAX_VALUE), parseEditorValue("long", "9223372036854775807"))
    }

    @Test fun copyingActionRegeneratesAllNestedIds() {
        val original = Component(type = "action.if", children = listOf(Component(type = "action.loop", children = listOf(Component(type = "action.stop")))), elseActions = listOf(Component(type = "action.stop")))
        val copy = copyEditorComponent(original)
        assertEquals(original.type, copy.type)
        assertNotEquals(original.id, copy.id)
        assertNotEquals(original.children[0].children[0].id, copy.children[0].children[0].id)
        assertNotEquals(original.elseActions[0].id, copy.elseActions[0].id)
    }
}
