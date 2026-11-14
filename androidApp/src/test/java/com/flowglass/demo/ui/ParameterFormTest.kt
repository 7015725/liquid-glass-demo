package com.flowglass.demo.ui

import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ParamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The task editor builds a form from ParamSpec; these tests prove the form can round-trip every
 * built-in component without depending on Compose.
 */
class ParameterFormTest {
    private val registry = BuiltInComponents.registry()

    @Test fun everyBuiltInComponentRoundTripsThroughItsFields() {
        val components = registry.all().filter { it.params.isNotEmpty() }
        assertTrue("目录不应为空", components.isNotEmpty())
        for (meta in components) {
            val option = CommandCatalog.options(CommandSection.ACTIONS)
                .firstOrNull { it.typeId == meta.typeId }
                ?: CommandCatalog.options(CommandSection.CONDITIONS).firstOrNull { it.typeId == meta.typeId }
                ?: CommandCatalog.options(CommandSection.TRIGGERS).firstOrNull { it.typeId == meta.typeId }
            val source = option?.defaultParameter
                ?: com.flowglass.engine.ParameterCodec.formatComponent(
                    meta, meta.params.associate { spec -> spec.key to spec.defaultValue },
                )
            val fields = ParameterForm.fields(meta, source)
            assertEquals("${meta.typeId} 的字段数", meta.params.size, fields.size)
            val composed = ParameterForm.compose(meta, fields)
            assertTrue("${meta.typeId} 应可回写：${composed.exceptionOrNull()?.message}", composed.isSuccess)
            assertEquals("${meta.typeId} 往返后应保持默认值", source, composed.getOrThrow())
        }
    }

    @Test fun editingOneFieldKeepsTheOthers() {
        val meta = requireNotNull(registry.find("action.post_notification"))
        val fields = ParameterForm.fields(meta, "标题|正文").toMutableMap()
        fields["title"] = "新的标题"
        val composed = ParameterForm.compose(meta, fields).getOrThrow()
        assertEquals("新的标题|正文", composed)
    }

    @Test fun anInvalidFieldIsRejectedWithAReason() {
        val meta = requireNotNull(registry.find("action.set_brightness"))
        val fields = ParameterForm.fields(meta, "50%").toMutableMap()
        fields["value"] = "150%"
        val result = ParameterForm.compose(meta, fields)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("百分比"))
    }

    @Test fun contentMayContainTheSeparatorBecauseOnlyTheFirstOneSplits() {
        val meta = requireNotNull(registry.find("action.post_notification"))
        val fields = ParameterForm.fields(meta, "标题|正文").toMutableMap()
        fields["content"] = "含|分隔符"
        val composed = ParameterForm.compose(meta, fields).getOrThrow()
        assertEquals("标题|含|分隔符", composed)
        val back = com.flowglass.engine.ParameterCodec.parseComponent(meta, composed)
        assertEquals("\"含|分隔符\"", back.getValue("content").toString())
    }

    @Test fun aNotificationTitleCannotContainTheFieldSeparator() {
        val meta = requireNotNull(registry.find("action.post_notification"))
        val fields = ParameterForm.fields(meta, "标题|正文").toMutableMap()
        fields["title"] = "含|分隔符"
        assertTrue(ParameterForm.compose(meta, fields).isFailure)
    }

    @Test fun everyParamTypeInTheCatalogHasAField() {
        val meta = requireNotNull(registry.find("condition.battery_range"))
        assertEquals(listOf("range"), meta.params.map { it.key })
        val fields = ParameterForm.fields(meta, "20%–80%")
        assertEquals("20%–80%", fields["range"])
        assertEquals(ParamType.INT_RANGE, meta.params.single().type)
        assertEquals(ComponentKind.CONDITION, meta.kind)
    }
}
