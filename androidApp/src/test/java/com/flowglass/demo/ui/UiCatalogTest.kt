package com.flowglass.demo.ui

import android.Manifest
import com.flowglass.demo.system.Perms
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ComponentKind
import com.flowglass.engine.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The add panel and the editor's permission hint must follow the component registry, not a
 * hand-written catalogue. These tests fail if a new trigger/action is added to the engine but
 * stays invisible in the UI, or if an id is listed twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiCatalogTest {
    private val registry = BuiltInComponents.registry()

    @Test
    fun triggerOptionsMirrorTheRegistry() {
        val expected = registry.all()
            .filter { it.kind == ComponentKind.TRIGGER }
            .map { it.typeId }
            .filterNot { it == "trigger.battery_low" || it == "trigger.charging" }
            .toSet()
        val actual = CommandCatalog.options(CommandSection.TRIGGERS).map { it.typeId }
        assertEquals(expected, actual.toSet())
        assertEquals(actual.size, actual.toSet().size)
    }

    @Test
    fun actionAndConditionOptionsMirrorTheRegistry() {
        listOf(CommandSection.ACTIONS to ComponentKind.ACTION, CommandSection.CONDITIONS to ComponentKind.CONDITION)
            .forEach { (section, kind) ->
                val expected = registry.all().filter { it.kind == kind }.map { it.typeId }.toSet()
                assertEquals(section.name, expected, CommandCatalog.options(section).map { it.typeId }.toSet())
            }
    }

    @Test
    fun everyOptionCarriesCategoryAndTierBadgeData() {
        CommandSection.entries.forEach { section ->
            CommandCatalog.options(section).forEach { option ->
                assertTrue("${option.typeId} 缺少分类", option.categoryName.isNotBlank())
                assertTrue("${option.typeId} 缺少图标", option.ic.name.isNotBlank())
                if (option.minTier != Tier.NORMAL) {
                    assertTrue("${option.typeId} 缺少层级徽标", option.minTier.badgeLabel.isNotBlank())
                }
            }
        }
    }

    @Test
    fun triggerCategoriesComeFromMetadata() {
        val declared = registry.all().filter { it.kind == ComponentKind.TRIGGER }.map { it.category }
        val filters = autoFilters()
        assertEquals(listOf("全部", "已开启"), filters.take(2))
        assertTrue(filters.containsAll(declared))
        assertEquals(declared.distinct(), filters.drop(2))
    }

    @Test
    fun ruleCategoryFollowsItsFirstTrigger() {
        val cmd = Cmd(
            id = 1, auto = true, ic = Ic.Clock, title = "测试", desc = "",
            triggers = listOf(CommandItem(1, "时间", "08:00", Ic.Clock, "trigger.time")),
            actions = emptyList(), category = 0,
        )
        assertEquals("定时", cmd.categoryName())
    }

    @Test
    fun legacyRuleWithoutTypeIdFallsBackToItsSavedBucket() {
        val cmd = Cmd(
            id = 1, auto = true, ic = Ic.Bell, title = "旧规则", desc = "",
            triggers = listOf(CommandItem(1, "通知", "验证码", Ic.Bell, null)),
            actions = emptyList(), category = 3,
        )
        assertEquals("通知", cmd.categoryName())
    }

    @Test
    fun missingPermissionHintUsesPermsChecks() {
        val app = RuntimeEnvironment.getApplication()
        val cmd = Cmd(
            id = 1, auto = false, ic = Ic.Bell, title = "通知我", desc = "",
            actions = listOf(CommandItem(1, "发送通知", "完成", Ic.Bell, "action.post_notification")),
        )

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(listOf("notify"), cmd.missingPermissions(app).map { it.key })

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue("已授权时不应提示", cmd.missingPermissions(app).isEmpty())
    }

    @Test
    fun stepsWithoutPermissionsNeverAskForOne() {
        val app = RuntimeEnvironment.getApplication()
        val cmd = Cmd(
            id = 1, auto = false, ic = Ic.Copy, title = "复制", desc = "",
            actions = listOf(CommandItem(1, "复制到剪贴板", "文本", Ic.Copy, "action.copy_clipboard")),
        )
        assertEquals(null, cmd.actions.single().permissionKey())
        assertTrue(cmd.missingPermissions(app).isEmpty())
    }

    @Test
    fun permissionKeysAreDeclaredMetadataKeys() {
        val declaredKeys = Perms.all.map { it.key }.toSet()
        listOf(CommandItem(1, "发送通知", "", Ic.Bell, "action.post_notification"),
            CommandItem(2, "开关勿扰", "开启", Ic.Moon, "action.dnd_switch"),
            CommandItem(3, "调节亮度", "50%", Ic.Gauge, "action.set_brightness"))
            .forEach { item ->
                val key = item.permissionKey()
                assertTrue("${item.name} 的权限键不在 Perms 中", key in declaredKeys)
            }
    }
}
