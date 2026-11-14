package com.flowglass.demo.system

import android.content.pm.PackageManager
import com.flowglass.engine.BuiltInComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComponentPermissionsTest {
    @Test fun everyComponentPermissionKeyHasACatalogEntry() {
        val declared = BuiltInComponents.all.flatMap { it.permissions }.toSet()
        val catalog = Perms.all.map { it.key }.toSet()
        assertEquals(emptySet<String>(), declared - catalog)
    }

    @Test fun newTriggerAndActionPermissionsAreDeclared() {
        val metas = BuiltInComponents.all.associateBy { it.typeId }
        assertEquals(setOf("notification_listener"), metas.getValue("trigger.notification").permissions.toSet())
        assertEquals(setOf("foreground_app"), metas.getValue("trigger.app_foreground").permissions.toSet())
        assertEquals(setOf("write_settings"), metas.getValue("action.screen_timeout").permissions.toSet())
        assertEquals(setOf("write_settings"), metas.getValue("action.auto_rotate").permissions.toSet())
        assertEquals(setOf("a11y"), metas.getValue("action.global_action").permissions.toSet())
        assertEquals(com.flowglass.engine.Tier.ROOT, metas.getValue("action.airplane_mode").minTier)
        assertEquals(com.flowglass.engine.Tier.ROOT, metas.getValue("action.mobile_data").minTier)
    }

    @Test fun manifestDeclaresServicesAndNetworkStatePermission() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS,
        )
        val services = info.services.orEmpty().map { it.name }
        assertTrue(services.contains("com.flowglass.demo.system.trigger.FlowNotificationListener"))
        assertTrue(services.contains("com.flowglass.demo.system.trigger.TriggerService"))
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertTrue(permissions.contains(android.Manifest.permission.ACCESS_NETWORK_STATE))
        assertTrue(permissions.contains(android.Manifest.permission.VIBRATE))
    }
}
