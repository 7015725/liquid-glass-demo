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
            val source = if (meta.typeId == "trigger.app_method_call") {
                // Its empty defaults are intentionally invalid until the user names a target,
                // class and method. Round-trip a realistic complete configuration instead.
                com.flowglass.engine.ParameterCodec.formatComponent(
                    meta,
                    meta.params.associate { spec ->
                        val sample = when (spec.key) {
                            "package" -> "com.example.app"
                            "className" -> "com.example.app.OrderManager"
                            "methodName" -> "onPaySuccess"
                            "paramTypes" -> "java.lang.String,int"
                            "extractions" -> "[]"
                            else -> spec.defaultValue.toString()
                        }
                        spec.key to com.flowglass.engine.ParameterCodec.parse(spec, sample)
                    },
                )
            } else {
                option?.defaultParameter
                    ?: com.flowglass.engine.ParameterCodec.formatComponent(
                        meta, meta.params.associate { spec -> spec.key to spec.defaultValue },
                    )
            }
            val fields = ParameterForm.fields(meta, source)
            assertEquals("${meta.typeId} 的字段数", meta.params.size, fields.size)
            val composed = ParameterForm.compose(meta, fields)
            assertTrue("${meta.typeId} 应可回写：${composed.exceptionOrNull()?.message}", composed.isSuccess)
            assertEquals("${meta.typeId} 往返后应保持默认值", source, composed.getOrThrow())
        }
    }

    @Test fun appMethodCallDraftNeedsACompletedTargetAndMethod() {
        val meta = requireNotNull(registry.find("trigger.app_method_call"))
        val fields = ParameterForm.fields(meta, "")
        assertEquals("", fields["package"])
        val empty = ParameterForm.compose(meta, fields)
        assertTrue(empty.isFailure)
        assertTrue(empty.exceptionOrNull()?.message.orEmpty().contains("目标应用"))
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

    @Test fun triggerRegexCanContainTheLegacySeparatorWithoutCorruptingFields() {
        val meta = requireNotNull(registry.find("trigger.notification"))
        val fields = ParameterForm.fields(meta, "").toMutableMap().apply {
            put("title", "验证码|动态码")
            put("text", "\\d{6}")
            put("matchMode", "正则表达式")
        }
        val composed = ParameterForm.compose(meta, fields).getOrThrow()
        assertTrue(composed.trimStart().startsWith("{"))
        val reopened = ParameterForm.fields(meta, composed)
        assertEquals("验证码|动态码", reopened["title"])
        assertEquals("\\d{6}", reopened["text"])
        assertEquals("正则表达式", reopened["matchMode"])
    }

    @Test fun triggerValidationIgnoresHiddenLegacyTokenAndChecksLocationAndMethod() {
        val intent = requireNotNull(registry.find("trigger.intent_received"))
        val intentFields = ParameterForm.fields(intent, "")
        // The token editor is gone; an old short token must not block saving.
        assertEquals(null, ParameterForm.triggerProblem(intent, intentFields))
        assertEquals(null, ParameterForm.triggerProblem(intent, intentFields + ("token" to "short")))

        val location = requireNotNull(registry.find("trigger.location_enter"))
        val locationFields = ParameterForm.fields(location, "")
        assertTrue(
            ParameterForm.triggerProblem(location, locationFields)
                .orEmpty().contains("纬度"),
        )
        assertEquals(null, ParameterForm.triggerProblem(location,
            locationFields + ("location" to "39.9042,116.4074")))

        val method = requireNotNull(registry.find("trigger.app_method_call"))
        val methodFields = ParameterForm.fields(method, "")
            .toMutableMap().apply {
                put("package", "com.example.app")
                put("methodName", "onPay")
            }
        assertTrue(
            ParameterForm.triggerProblem(method, methodFields)
                .orEmpty().contains("类名"),
        )
    }

    @Test fun triggerValidationRejectsInvalidRegexBeforeSave() {
        val notification = requireNotNull(registry.find("trigger.notification"))
        val fields = ParameterForm.fields(notification, "").toMutableMap().apply {
            put("title", "(")
            put("matchMode", "正则表达式")
        }
        assertTrue(
            ParameterForm.triggerProblem(notification, fields)
                .orEmpty().contains("正则"),
        )
    }

    @Test fun triggerValidationLeavesOptionalNotificationFiltersAlone() {
        val notification = requireNotNull(registry.find("trigger.notification"))
        val fields = ParameterForm.fields(notification, "")
        assertEquals(null, ParameterForm.triggerProblem(notification, fields))
    }
}
