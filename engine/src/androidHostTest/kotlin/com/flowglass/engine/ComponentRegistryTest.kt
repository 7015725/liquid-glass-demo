package com.flowglass.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComponentRegistryTest {
    @Test
    fun registersAndFindsComponentsByTypeId() {
        val registry = ComponentRegistry()
        val component = ComponentMeta(
            typeId = "action.test",
            name = "测试动作",
            category = "测试",
            kind = ComponentKind.ACTION,
        )

        registry.register(component)

        assertEquals(component, registry.find("action.test"))
        assertEquals(listOf(component), registry.byCategory("测试"))
    }

    @Test
    fun duplicateTypeIdsAreRejected() {
        val registry = ComponentRegistry()
        val first = ComponentMeta("action.test", "动作", "测试", ComponentKind.ACTION)
        val duplicate = first.copy(name = "重复动作")
        registry.register(first)

        assertFailsWith<IllegalArgumentException> { registry.register(duplicate) }
    }
}
