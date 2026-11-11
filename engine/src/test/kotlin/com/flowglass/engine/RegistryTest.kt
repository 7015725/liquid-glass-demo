package com.flowglass.engine

import org.junit.Assert.*
import org.junit.Test

class RegistryTest {
    @Test fun documentCatalogHasTypedFormsAndHonestCapabilityMetadata() {
        val expected = mapOf(
            Kind.TRIGGER to "wifi_connected wifi_disconnected bluetooth_connected network_changed notification notification_removed time interval sun_event charging screen battery app_opened app_closed global_variable_changed headphones ui_text setting_changed clipboard shake location key log file_changed foreground_changed app_installed permission_prompt",
            Kind.CONDITION to "time_range variable wifi charging battery expression weekday app_foreground",
            Kind.ACTION to "set_ringer list_append shell notification open_app delay javascript variable_set variable_calculate variable_convert variable_delete list_insert list_remove list_get list_length list_find list_sort list_foreach dict_set dict_get dict_delete if loop stop call_manual wait_event"
        )
        expected.forEach { (kind, names) -> names.split(" ").forEach { name ->
            val id = kind.name.lowercase() + "." + name
            val meta = ComponentRegistry.find(id)
            assertNotNull(id, meta)
            assertEquals(kind, meta!!.kind)
            assertTrue(id, meta.name.isNotBlank() && meta.category.isNotBlank() && meta.description.isNotBlank())
            assertTrue(id, meta.params.map { it.key }.distinct().size == meta.params.size)
            meta.params.forEach { assertTrue(it.kind in listOf("text", "long", "double", "bool", "choice", "json", "code", "time")) }
            val a = ComponentRegistry.newComponent(id)
            val b = ComponentRegistry.newComponent(id)
            assertNotEquals(a.id, b.id)
            assertEquals(meta.params.associate { it.key to it.defaultValue }, a.params)
        } }
        assertEquals(ComponentRegistry.items.size, ComponentRegistry.items.distinctBy { it.typeId }.size)
        assertTrue(ComponentRegistry.find("trigger.notification")!!.permissions.isNotEmpty())
        assertEquals("ROOT", ComponentRegistry.find("trigger.log")!!.minTier)
        assertEquals("XPOSED", ComponentRegistry.find("trigger.permission_prompt")!!.minTier)
        assertTrue(ComponentRegistry.find("action.shell")!!.outputs.contains("shell.stdout"))
        assertTrue(ComponentRegistry.find("condition.variable")!!.params.first { it.key == "op" }.choices.containsAll(listOf("eq", "gt", "lt", "contains", "empty", "list_contains", "regex")))
        assertThrows(IllegalArgumentException::class.java) { ComponentRegistry.newComponent("unknown") }
    }
}
