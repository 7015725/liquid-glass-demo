package com.flowglass.demo.system.trigger

import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.LegacyCommandMigration
import com.flowglass.engine.ParameterCodec
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerRule

private val componentRegistry = BuiltInComponents.registry()

internal fun Cmd.toTriggerRule(): TriggerRule = TriggerRule(
    id = LegacyCommandMigration.uuidForLegacyId(id),
    cmdId = id,
    name = title,
    enabled = enabled,
    triggers = triggers.map { it.toTriggerComponent() },
    conditions = conditions.map { it.toTriggerComponent() },
    permissions = (triggers + conditions + actions).mapNotNull { item ->
        item.typeId?.let(componentRegistry::find)
    }.flatMap { it.permissions }.distinct(),
)

internal fun CommandItem.toTriggerComponent(): TriggerComponent {
    val type = this.typeId
    val meta = type?.let(componentRegistry::find)
    val parsed = meta?.let { runCatching { ParameterCodec.parseComponent(it, parameter) }.getOrNull() }
    return TriggerComponent(
        typeId = if (meta != null && parsed != null) meta.typeId else "legacy.unknown",
        name = meta?.name ?: name,
        params = parsed.orEmpty(),
    )
}

internal fun Cmd.hasSupportedTrigger(): Boolean = triggers.any {
    com.flowglass.engine.trigger.TriggerTypes.sourceOf(it.typeId.orEmpty()) != null
}

internal fun Cmd.hasUnsupportedTrigger(): Boolean = triggers.any {
    val type = it.typeId.orEmpty()
    type.isNotEmpty() && com.flowglass.engine.trigger.TriggerTypes.sourceOf(type) == null
}
