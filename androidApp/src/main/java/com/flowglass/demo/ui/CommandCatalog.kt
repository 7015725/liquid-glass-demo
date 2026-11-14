package com.flowglass.demo.ui

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
            "trigger.wifi", "trigger.bluetooth", "trigger.notification",
            "trigger.app_foreground", "trigger.headset" ->
                parameter.split('|').filter { it.isNotEmpty() }.joinToString(" · ")
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
)
fun Cmd.items(section: CommandSection): List<CommandItem> = when (section) {
    CommandSection.TRIGGERS -> triggers
    CommandSection.CONDITIONS -> conditions
    CommandSection.ACTIONS -> actions
}
object CommandCatalog {
    private fun icon(typeId: String) = when (typeId) {
        "trigger.time", "condition.time_range", "condition.weekday" -> Ic.Clock
        "trigger.location_enter", "trigger.location_exit" -> Ic.Location
        "trigger.notification_received", "trigger.notification", "action.post_notification" -> Ic.Bell
        "trigger.battery_low", "trigger.battery_level", "condition.battery_range",
        "condition.charging", "trigger.power_connected", "trigger.power_disconnected" -> Ic.Battery
        "trigger.screen_on", "trigger.screen_off", "trigger.user_present", "trigger.boot_completed",
        "condition.screen_state" -> Ic.Phone
        "trigger.charging", "action.wifi_switch", "trigger.wifi", "trigger.wifi_changed",
        "condition.network_type" -> Ic.Wifi
        "trigger.bluetooth_connected", "trigger.bluetooth", "trigger.headset", "action.bluetooth_switch" -> Ic.Bluetooth
        "trigger.application_started", "trigger.app_foreground", "action.launch_app" -> Ic.Grid
        "condition.dnd_state", "action.dnd_switch", "action.ringer_mode" -> Ic.Moon
        "action.set_brightness", "action.set_volume" -> Ic.Gauge
        "action.copy_clipboard" -> Ic.Copy
        "action.delay" -> Ic.Timer
        "action.root_shell" -> Ic.Terminal
        "action.http_request", "action.open_url" -> Ic.Share
        "action.vibrate", "action.flashlight" -> Ic.Bolt
        "action.media_control" -> Ic.Play
        "action.speak", "action.toast" -> Ic.Message
        "action.screen_timeout", "action.auto_rotate" -> Ic.Phone
        "action.global_action" -> Ic.Tap
        "action.airplane_mode", "action.mobile_data" -> Ic.Wifi
        else -> Ic.Refresh
    }

    private val categories = mapOf("定时" to 2, "通知" to 3, "位置" to 4, "设备" to 5, "网络" to 6, "应用" to 7)

    /** Superseded type ids stay readable in old rules but are not offered as new triggers. */
    private val hiddenFromCatalog = setOf("trigger.battery_low", "trigger.charging")
    private val registry = com.flowglass.engine.BuiltInComponents.registry()
    private val optionsByKind = registry.all().groupBy { it.kind }
        .mapValues { (_, metas) -> metas.map { meta ->
            val default = com.flowglass.engine.ParameterCodec.formatComponent(meta,
                meta.params.associate { it.key to it.defaultValue })
            CommandOption(
                name = meta.name,
                ic = icon(meta.typeId),
                defaultParameter = default,
                category = categories[meta.category] ?: 0,
                typeId = meta.typeId,
                categoryName = meta.category,
                minTier = meta.minTier,
                comingSoon = meta.comingSoon,
            )
        } }

    fun options(section: CommandSection): List<CommandOption> = when (section) {
        CommandSection.TRIGGERS -> optionsByKind[com.flowglass.engine.ComponentKind.TRIGGER].orEmpty()
            .filterNot { it.typeId in hiddenFromCatalog }
        CommandSection.CONDITIONS -> optionsByKind[com.flowglass.engine.ComponentKind.CONDITION].orEmpty()
        CommandSection.ACTIONS -> optionsByKind[com.flowglass.engine.ComponentKind.ACTION].orEmpty()
    }
}
