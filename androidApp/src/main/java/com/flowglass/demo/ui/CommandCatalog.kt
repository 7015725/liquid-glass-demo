package com.flowglass.demo.ui

import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.Tier

enum class CommandSection { TRIGGERS, CONDITIONS, ACTIONS }
data class CommandItem(
    val id: Long,
    val name: String,
    val parameter: String,
    val ic: Ic,
    val typeId: String? = null,
    /** 失败策略：STOP 失败即停 / CONTINUE 继续 / RETRY 重试一次。 */
    val onError: com.flowglass.engine.OnError = com.flowglass.engine.OnError.STOP,
) {
    val display: String get() {
        val text = when (typeId) {
            "action.post_notification" -> notificationDisplayText(parameter)
            "trigger.time" -> parameter.split('|').joinToString(" · ")
            "trigger.battery_level" -> parameter.split('|').joinToString(" ").trim()
            else -> parameter
        }
        return if (text.isEmpty()) name else "$name：$text"
    }
    override fun toString() = display
}

/**
 * The notification parameter stores `title|content` for compatibility. User-facing rows must
 * never show that separator: an empty title shows only the content; a non-empty title uses the
 * `标题 · 内容` form. A body may contain further separators and is kept intact.
 */
internal fun notificationDisplayText(parameter: String): String {
    val split = parameter.indexOf('|')
    if (split < 0) return parameter
    val title = parameter.substring(0, split)
    val content = parameter.substring(split + 1)
    return when {
        title.isEmpty() -> content
        content.isEmpty() -> title
        else -> "$title · $content"
    }
}
data class CommandOption(
    val name: String,
    val ic: Ic,
    val defaultParameter: String,
    val category: Int = 0,
    val typeId: String? = null,
    val categoryName: String = "设备",
    val minTier: com.flowglass.engine.Tier = com.flowglass.engine.Tier.NORMAL,
    /** Present in the catalog for discoverability but not selectable in this stage. */
    val comingSoon: Boolean = false,
    /** Metadata description, or the parameter labels when it has none. */
    val detail: String = "",
    val permissions: List<String> = emptyList(),
)
fun Cmd.items(section: CommandSection): List<CommandItem> = when (section) {
    CommandSection.TRIGGERS -> triggers
    CommandSection.CONDITIONS -> conditions
    CommandSection.ACTIONS -> actions
}

/** Badge copy for a component's minimum privilege tier. */
internal val Tier.badgeLabel: String
    get() = when (this) {
        Tier.NORMAL -> ""
        Tier.SHIZUKU -> "需 Shizuku"
        Tier.ROOT -> "需 Root"
        Tier.XPOSED -> "需 Xposed"
    }

/**
 * The add panel is driven entirely by [com.flowglass.engine.BuiltInComponents]: the list,
 * the categories and the tier badges come from each [ComponentMeta]. A component merged in
 * another branch therefore shows up here without touching this file. Icons are cosmetic:
 * a new typeId falls back to its metadata category, then to its kind.
 */
object CommandCatalog {
    private val registry = com.flowglass.engine.BuiltInComponents.registry()

    /** Precise icons for the ids this build knows; unknown ids fall back to the category. */
    private fun icon(typeId: String): Ic? = when (typeId) {
        "trigger.time", "condition.time_range", "condition.weekday" -> Ic.Clock
        "trigger.location_enter", "trigger.location_exit" -> Ic.Location
        "trigger.notification_received", "action.post_notification" -> Ic.Bell
        "trigger.battery_low", "trigger.battery_level", "condition.battery_range",
        "condition.charging", "trigger.power_connected", "trigger.power_disconnected" -> Ic.Battery
        "trigger.screen_on", "trigger.screen_off", "trigger.user_present", "trigger.boot_completed",
        "condition.screen_state" -> Ic.Phone
        "trigger.charging", "action.wifi_switch" -> Ic.Wifi
        "trigger.bluetooth_connected", "action.bluetooth_switch" -> Ic.Bluetooth
        "trigger.wifi_changed", "condition.network_type" -> Ic.Wifi
        "trigger.application_started", "action.launch_app" -> Ic.Grid
        "condition.dnd_state", "action.dnd_switch" -> Ic.Moon
        "action.set_brightness", "action.set_volume" -> Ic.Gauge
        "action.copy_clipboard" -> Ic.Copy
        "action.delay" -> Ic.Timer
        "action.root_shell" -> Ic.Terminal
        else -> null
    }

    /** Category icons keep new components legible without a per-id entry. */
    internal fun categoryIcon(category: String, kind: ComponentKind? = null): Ic = when (category) {
        "定时" -> Ic.Clock
        "通知" -> Ic.Bell
        "位置" -> Ic.Location
        "网络" -> Ic.Wifi
        "应用" -> Ic.Grid
        "脚本" -> Ic.Terminal
        "文本" -> Ic.Copy
        "流程控制" -> Ic.Timer
        "设备" -> Ic.Phone
        else -> when (kind) {
            ComponentKind.TRIGGER -> Ic.Bolt
            ComponentKind.CONDITION -> Ic.Shield
            ComponentKind.ACTION -> Ic.Play
            null -> Ic.Puzzle
        }
    }

    fun meta(typeId: String): ComponentMeta? = registry.find(typeId)

    /** Categories in registry order: 定时, 设备, 位置, 通知, 网络, 应用 ... */
    fun categories(section: CommandSection): List<String> = options(section).map { it.categoryName }.distinct()

    /**
     * Legacy sidecar buckets (`Cmd.category`, persisted as 0..6). Only used to classify
     * rules whose trigger id is missing from the current registry.
     */
    private val legacyBuckets = mapOf("定时" to 2, "通知" to 3, "位置" to 4, "设备" to 5, "网络" to 6, "应用" to 5)

    internal fun legacyBucket(category: String): Int = legacyBuckets[category] ?: 0

    /** Reverse of [legacyBuckets] for rules saved before triggers carried type ids. */
    internal fun legacyCategoryName(bucket: Int): String? = when (bucket) {
        2 -> "定时"
        3 -> "通知"
        4 -> "位置"
        5 -> "设备"
        6 -> "网络"
        else -> null
    }

    /** Superseded type ids stay readable in old rules but are not offered as new triggers. */
    private val hiddenFromCatalog = setOf("trigger.battery_low", "trigger.charging")

    private val optionsByKind = registry.all().groupBy { it.kind }
        .mapValues { (_, metas) -> metas.map { meta ->
            val default = com.flowglass.engine.ParameterCodec.formatComponent(meta,
                meta.params.associate { it.key to it.defaultValue })
            CommandOption(
                name = meta.name,
                ic = icon(meta.typeId) ?: categoryIcon(meta.category, meta.kind),
                defaultParameter = default,
                category = legacyBucket(meta.category),
                typeId = meta.typeId,
                categoryName = meta.category,
                minTier = meta.minTier,
                comingSoon = meta.comingSoon,
                detail = meta.description.ifBlank { meta.params.joinToString(" · ") { it.label } },
                permissions = meta.permissions,
            )
        } }

    fun options(section: CommandSection): List<CommandOption> = when (section) {
        CommandSection.TRIGGERS -> optionsByKind[ComponentKind.TRIGGER].orEmpty()
            .filterNot { it.typeId in hiddenFromCatalog }
        CommandSection.CONDITIONS -> optionsByKind[ComponentKind.CONDITION].orEmpty()
        CommandSection.ACTIONS -> optionsByKind[ComponentKind.ACTION].orEmpty()
    }
}
