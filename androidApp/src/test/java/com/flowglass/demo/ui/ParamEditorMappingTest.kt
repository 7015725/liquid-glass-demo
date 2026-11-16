package com.flowglass.demo.ui

import com.flowglass.engine.ApplicationScope
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ParamEditor
import com.flowglass.engine.ParamSource
import com.flowglass.engine.resolvedEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The renderer is metadata-driven; this test is the inventory/guard for future components. */
class ParamEditorMappingTest {
    private val registry = BuiltInComponents.registry()

    @Test fun everyBuiltInParameterResolvesToAConcreteEditor() {
        val all = registry.all()
        assertEquals("组件数量", 56, all.size)
        assertEquals("参数数量", 71, all.sumOf { it.params.size })
        all.forEach { meta ->
            meta.params.forEach { spec ->
                assertNotEquals(
                    "${meta.typeId}.${spec.key} must not fall through to AUTO",
                    ParamEditor.AUTO,
                    spec.resolvedEditor(),
                )
            }
        }
    }

    @Test fun storageTypesKeepTheirExistingPresentationByDefault() {
        fun editor(typeId: String, key: String): ParamEditor =
            requireNotNull(registry.find(typeId)).params.single { it.key == key }.resolvedEditor()

        assertEquals(ParamEditor.TIME, editor("trigger.time", "time"))
        assertEquals(ParamEditor.TIME_RANGE, editor("condition.time_range", "range"))
        assertEquals(ParamEditor.INT_RANGE, editor("condition.battery_range", "range"))
        assertEquals(ParamEditor.APPLICATION, editor("trigger.notification", "app"))
        assertEquals(ParamEditor.APPLICATION, editor("action.launch_app", "package"))
        assertEquals(ParamEditor.SEGMENTED, editor("action.wifi_switch", "mode"))
    }

    @Test fun onlySemanticOverridesUseDedicatedPickers() {
        val runRule = requireNotNull(registry.find("action.run_rule")).params.single { it.key == "rule" }
        assertEquals(ParamEditor.RULE, runRule.resolvedEditor())
        assertEquals(ParamSource.RULES, runRule.source)

        val location = requireNotNull(registry.find("trigger.location_enter")).params.single { it.key == "location" }
        assertEquals(ParamEditor.LOCATION, location.resolvedEditor())
        assertEquals(ParamSource.CURRENT_LOCATION, location.source)
    }

    @Test fun dynamicSourcesAreDeclaredOnTheSpecNotTheComponentId() {
        val bluetooth = requireNotNull(registry.find("trigger.bluetooth")).params.single { it.key == "name" }
        assertEquals(ParamSource.PAIRED_BLUETOOTH, bluetooth.source)
        val wifi = requireNotNull(registry.find("trigger.wifi")).params.single { it.key == "ssid" }
        assertEquals(ParamSource.WIFI_NETWORKS, wifi.source)
    }

    @Test fun foregroundApplicationScopeIsDeclaredByTheParameterNotTheComponentId() {
        val foreground = requireNotNull(registry.find("trigger.app_foreground")).params.single { it.key == "package" }
        assertEquals(ApplicationScope.ALL_PROFILES, foreground.applicationScope)
        assertEquals(ApplicationScope.ALL_PROFILES,
            requireNotNull(registry.find("trigger.notification")).params.single { it.key == "app" }.applicationScope)
        assertEquals(ApplicationScope.ALL_PROFILES,
            requireNotNull(registry.find("action.launch_app")).params.single { it.key == "package" }.applicationScope)
    }
}
