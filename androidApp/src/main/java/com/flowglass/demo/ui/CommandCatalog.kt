package com.flowglass.demo.ui

enum class CommandSection { TRIGGERS, CONDITIONS, ACTIONS }
data class CommandItem(val id: Long, val name: String, val parameter: String, val ic: Ic, val typeId: String? = null) {
    val display: String get() = if (parameter.isEmpty()) name else "$name：$parameter"
    override fun toString() = display
}
data class CommandOption(
    val name: String,
    val ic: Ic,
    val defaultParameter: String,
    val category: Int = 0,
    val typeId: String? = null,
    val categoryName: String = "设备",
    val minTier: com.flowglass.engine.Tier = com.flowglass.engine.Tier.NORMAL,
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
        "trigger.notification_received", "action.post_notification" -> Ic.Bell
        "trigger.battery_low", "condition.battery_range" -> Ic.Battery
        "trigger.charging", "action.wifi_switch" -> Ic.Wifi
        "trigger.bluetooth_connected", "action.bluetooth_switch" -> Ic.Bluetooth
        "trigger.wifi_changed", "condition.network_type" -> Ic.Wifi
        "trigger.application_started", "action.launch_app" -> Ic.Grid
        "trigger.boot_completed", "condition.screen_state" -> Ic.Phone
        "condition.dnd_state", "action.dnd_switch" -> Ic.Moon
        "action.set_brightness", "action.set_volume" -> Ic.Gauge
        "action.copy_clipboard" -> Ic.Copy
        "action.delay" -> Ic.Timer
        "action.root_shell" -> Ic.Terminal
        else -> Ic.Refresh
    }

    private val categories = mapOf("定时" to 2, "通知" to 3, "位置" to 4, "设备" to 5, "网络" to 6, "应用" to 5)
    private val registry = com.flowglass.engine.BuiltInComponents.registry()
    private val optionsByKind = registry.all().groupBy { it.kind }
        .mapValues { (_, metas) -> metas.map { meta ->
            val default = meta.params.firstOrNull()?.defaultValue
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
            CommandOption(
                name = meta.name,
                ic = icon(meta.typeId),
                defaultParameter = default,
                category = categories[meta.category] ?: 0,
                typeId = meta.typeId,
                categoryName = meta.category,
                minTier = meta.minTier,
            )
        } }

    fun options(section: CommandSection): List<CommandOption> = when (section) {
        CommandSection.TRIGGERS -> optionsByKind[com.flowglass.engine.ComponentKind.TRIGGER].orEmpty()
        CommandSection.CONDITIONS -> optionsByKind[com.flowglass.engine.ComponentKind.CONDITION].orEmpty()
        CommandSection.ACTIONS -> optionsByKind[com.flowglass.engine.ComponentKind.ACTION].orEmpty()
    }
}
