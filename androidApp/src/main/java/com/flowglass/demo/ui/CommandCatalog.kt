package com.flowglass.demo.ui

import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.Tier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

enum class CommandSection { TRIGGERS, CONDITIONS, ACTIONS }
data class CommandItem(
    val id: Long,
    val name: String,
    val parameter: String,
    val ic: Ic,
    val typeId: String? = null,
    /** 失败策略：STOP 失败即停 / CONTINUE 继续 / RETRY 重试一次。 */
    val onError: com.flowglass.engine.OnError = com.flowglass.engine.OnError.STOP,
    /** v3：如果的 then、循环的循环体。 */
    val children: List<CommandItem> = emptyList(),
    /** v3：如果的否则如果/否则分支。 */
    val branches: List<CommandBranch> = emptyList(),
    /** v3：如果的分支条件、while 循环条件、wait_until 条件。 */
    val conditions: List<CommandItem> = emptyList(),
    /** C2：折叠状态，只影响编辑页显示。 */
    val collapsed: Boolean = false,
) {
    val display: String get() {
        val text = when (typeId) {
            "action.post_notification" -> notificationDisplayText(parameter)
            "action.toast" -> toastDisplayText(parameter)
            "action.script" -> runCatching {
                val script = kotlinx.serialization.json.Json.parseToJsonElement(parameter)
                    .let { (it as? kotlinx.serialization.json.JsonObject) }
                    ?.get("script")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: parameter
                "JavaScript · ${script.lineSequence().count()} 行"
            }.getOrDefault("JavaScript")
            "trigger.time" -> parameter.split('|').joinToString(" · ")
            "trigger.battery_level" -> parameter.split('|').joinToString(" ").trim()
            "trigger.location_enter", "trigger.location_exit" ->
                parameter.split('|').filter { it.isNotEmpty() }.joinToString(" · ")
            "trigger.wifi", "trigger.bluetooth", "trigger.notification",
            "trigger.app_foreground", "trigger.app_process", "trigger.app_crash", "trigger.keyguard",
            "trigger.media", "trigger.foreground_service", "trigger.headset", "trigger.method_hook" ->
                parameter.split('|').filter { it.isNotEmpty() }.joinToString(" · ")
            "condition.app_process_state" -> parameter.split('|').joinToString(" · ")
            else -> parameter
        }
        return if (text.isEmpty()) name else "$name：$text"
    }
    override fun toString() = display
}

data class CommandBranch(
    val children: List<CommandItem> = emptyList(),
    val conditions: List<CommandItem> = emptyList(),
)

/** Depth-first walk: the node itself first, then conditions, body and branches. */
fun List<CommandItem>.allItems(): List<CommandItem> = flatMap { item ->
    listOf(item) + item.conditions.allItems() + item.children.allItems() +
        item.branches.flatMap { branch -> branch.conditions.allItems() + branch.children.allItems() }
}

fun List<CommandItem>.findItem(id: Long): CommandItem? =
    firstOrNull { it.id == id } ?: firstNotNullOfOrNull { item ->
        item.conditions.findItem(id) ?: item.children.findItem(id)
            ?: item.branches.firstNotNullOfOrNull { it.conditions.findItem(id) ?: it.children.findItem(id) }
    }

fun List<CommandItem>.updateItem(id: Long, change: (CommandItem) -> CommandItem): List<CommandItem> = map { item ->
    if (item.id == id) change(item) else item.copy(
        conditions = item.conditions.updateItem(id, change),
        children = item.children.updateItem(id, change),
        branches = item.branches.map { branch ->
            branch.copy(
                conditions = branch.conditions.updateItem(id, change),
                children = branch.children.updateItem(id, change),
            )
        },
    )
}

fun List<CommandItem>.removeItem(id: Long): List<CommandItem> =
    filterNot { it.id == id }.map { item ->
        item.copy(
            conditions = item.conditions.removeItem(id),
            children = item.children.removeItem(id),
            branches = item.branches.map { branch ->
                branch.copy(conditions = branch.conditions.removeItem(id), children = branch.children.removeItem(id))
            },
        )
    }

/** Moves an item inside whichever sibling list contains it. */
fun List<CommandItem>.moveItem(id: Long, delta: Int): List<CommandItem> {
    val from = indexOfFirst { it.id == id }
    if (from >= 0) {
        val to = from + delta
        return if (to !in indices) this else toMutableList().apply { add(to, removeAt(from)) }
    }
    return map { item ->
        item.copy(
            conditions = item.conditions.moveItem(id, delta),
            children = item.children.moveItem(id, delta),
            branches = item.branches.map { branch ->
                branch.copy(conditions = branch.conditions.moveItem(id, delta), children = branch.children.moveItem(id, delta))
            },
        )
    }
}

/** Moves among siblings only; used when a drag preview is committed once. */
fun List<CommandItem>.moveItemTo(id: Long, newIndex: Int): List<CommandItem> {
    val from = indexOfFirst { it.id == id }
    if (from < 0 || isEmpty()) return this
    val to = newIndex.coerceIn(0, lastIndex)
    if (to == from) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * The notification parameter stores `title|content` for compatibility. User-facing rows must
 * never show that separator: an empty title shows only the content; a non-empty title uses the
 * `标题 · 内容` form. A body may contain further separators and is kept intact.
 */
internal fun notificationDisplayText(parameter: String): String {
    val structured = structuredOrNull(parameter, "content")
    if (structured != null) {
        val title = structured["title"]?.jsonPrimitive?.content.orEmpty()
        val content = structured["content"]?.jsonPrimitive?.content.orEmpty()
        return when {
            title.isEmpty() -> content
            content.isEmpty() -> title
            else -> "$title · $content"
        }
    }
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

internal fun toastDisplayText(parameter: String): String =
    structuredOrNull(parameter, "text")?.get("text")?.jsonPrimitive?.content ?: parameter

private fun structuredOrNull(parameter: String, key: String): JsonObject? {
    if (!parameter.trimStart().startsWith("{")) return null
    return runCatching { Json.parseToJsonElement(parameter) as? JsonObject }
        .getOrNull()?.takeIf { it[key] is JsonPrimitive }
}
data class CommandOption(
    val name: String,
    val ic: Ic,
    val defaultParameter: String,
    val category: Int = 0,
    val typeId: String? = null,
    val categoryName: String = "设备",
    /** Presentation badge only; availability must use [supports], never this ordinal. */
    val minTier: com.flowglass.engine.Tier = com.flowglass.engine.Tier.NORMAL,
    /** Layers declared by [com.flowglass.engine.ComponentMeta]; picker availability uses these. */
    val supports: List<com.flowglass.engine.LayerSupport> = emptyList(),
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
        Tier.XPOSED -> "需 LSPosed"
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
        "trigger.notification_received", "trigger.notification", "action.post_notification" -> Ic.Bell
        "trigger.battery_low", "trigger.battery_level", "condition.battery_range",
        "condition.charging", "trigger.power_connected", "trigger.power_disconnected" -> Ic.Battery
        "trigger.screen_on", "trigger.screen_off", "trigger.user_present", "trigger.boot_completed",
        "condition.screen_state" -> Ic.Phone
        "trigger.charging", "action.wifi_switch", "trigger.wifi", "trigger.wifi_changed",
        "condition.network_type" -> Ic.Wifi
        "trigger.bluetooth_connected", "trigger.bluetooth", "trigger.headset", "action.bluetooth_switch" -> Ic.Bluetooth
        "trigger.application_started", "trigger.app_foreground", "action.launch_app",
        "action.force_stop", "trigger.method_hook" -> Ic.Grid
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
                supports = meta.supports,
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


/** Moves [id] into the children of its previous sibling when that sibling can host steps. */
fun List<CommandItem>.moveIntoPreviousBlock(id: Long, canHost: (CommandItem) -> Boolean): List<CommandItem> {
    val i = indexOfFirst { it.id == id }
    if (i >= 0) {
        if (i == 0 || !canHost(this[i - 1])) return this
        val item = this[i]
        val host = this[i - 1]
        return toMutableList().apply {
            set(i - 1, host.copy(children = host.children + item))
            removeAt(i)
        }
    }
    return map { item ->
        item.copy(
            conditions = item.conditions.moveIntoPreviousBlock(id, canHost),
            children = item.children.moveIntoPreviousBlock(id, canHost),
            branches = item.branches.map { branch ->
                branch.copy(
                    conditions = branch.conditions.moveIntoPreviousBlock(id, canHost),
                    children = branch.children.moveIntoPreviousBlock(id, canHost),
                )
            },
        )
    }
}

/** Moves [id] out of the block that owns it, placing it right after that block. */
fun List<CommandItem>.moveOutOfBlock(id: Long): List<CommandItem> {
    if (any { it.id == id }) return this
    for (index in indices) {
        val host = this[index]
        if (host.children.any { it.id == id }) {
            val child = host.children.first { it.id == id }
            return toMutableList().apply {
                set(index, host.copy(children = host.children.filterNot { it.id == id }))
                add(index + 1, child)
            }
        }
    }
    return map { item ->
        item.copy(
            conditions = item.conditions.moveOutOfBlock(id),
            children = item.children.moveOutOfBlock(id),
            branches = item.branches.map { branch ->
                branch.copy(
                    conditions = branch.conditions.moveOutOfBlock(id),
                    children = branch.children.moveOutOfBlock(id),
                )
            },
        )
    }
}

/** True when [id] has a previous sibling that accepts child steps. */
fun List<CommandItem>.canMoveIntoPreviousBlock(id: Long, canHost: (CommandItem) -> Boolean): Boolean {
    val i = indexOfFirst { it.id == id }
    if (i >= 0) return i > 0 && canHost(this[i - 1])
    return any { item ->
        item.conditions.canMoveIntoPreviousBlock(id, canHost) ||
            item.children.canMoveIntoPreviousBlock(id, canHost) ||
            item.branches.any { it.conditions.canMoveIntoPreviousBlock(id, canHost) || it.children.canMoveIntoPreviousBlock(id, canHost) }
    }
}

/** Registry-backed container check shared by the editor and the move-target panel. */
fun CommandItem.canHostSteps(): Boolean {
    val block = typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it)?.block }
    return block == com.flowglass.engine.BlockKind.IF || block == com.flowglass.engine.BlockKind.LOOP
}

/** Walks only steps and their bodies/branches; conditions are not movable steps. */
fun List<CommandItem>.actionNodes(): List<CommandItem> = flatMap { item ->
    listOf(item) + item.children.actionNodes() +
        item.branches.flatMap { branch -> branch.children.actionNodes() }
}

fun List<CommandItem>.findActionNode(id: Long): CommandItem? =
    firstOrNull { it.id == id } ?: firstNotNullOfOrNull { item ->
        item.children.findActionNode(id)
            ?: item.branches.firstNotNullOfOrNull { it.children.findActionNode(id) }
    }

private fun CommandItem.containsActionNode(id: Long): Boolean =
    children.any { it.id == id || it.containsActionNode(id) } ||
        branches.any { branch -> branch.children.any { it.id == id || it.containsActionNode(id) } }

internal fun List<CommandItem>.actionChildrenOf(parentId: Long?, branch: Int?): List<CommandItem>? {
    if (parentId == null) return if (branch == null) this else null
    val parent = findActionNode(parentId) ?: return null
    if (!parent.canHostSteps()) return null
    if (branch == null) return parent.children
    return parent.branches.getOrNull(branch)?.children
}

/**
 * Moves a step between any two action lists. [targetParentId] null means the root list;
 * a non-null branch selects an else-if/else body, otherwise the block body is used.
 *
 * [targetIndex] is the final index in the destination after the source is removed. Empty
 * destination lists accept only index 0. Invalid targets return null without changing the tree.
 */
fun List<CommandItem>.moveActionTo(
    sourceId: Long,
    targetParentId: Long?,
    targetBranch: Int?,
    targetIndex: Int,
    canHost: (CommandItem) -> Boolean = { it.canHostSteps() },
): List<CommandItem>? {
    val source = findActionNode(sourceId) ?: return null
    if (sourceId == targetParentId) return null
    if (targetParentId == null) {
        if (targetBranch != null) return null
    } else {
        val parent = findActionNode(targetParentId) ?: return null
        if (!canHost(parent)) return null
        if (targetBranch != null && targetBranch !in parent.branches.indices) return null
    }
    if (targetParentId != null && source.containsActionNode(targetParentId)) return null

    val targetList = actionChildrenOf(targetParentId, targetBranch) ?: return null
    val sourceInTarget = targetList.any { it.id == sourceId }
    val maxIndex = if (sourceInTarget) targetList.lastIndex else targetList.size
    if (targetIndex !in 0..maxIndex) return null

    val removed = removeItem(sourceId)
    val insertAt = targetIndex.coerceIn(0, actionChildrenOf(targetParentId, targetBranch)?.size ?: 0)
    return when {
        targetParentId == null -> removed.toMutableList().apply { add(insertAt, source) }
        targetBranch == null -> removed.updateItem(targetParentId) { parent ->
            val children = parent.children.toMutableList().apply { add(insertAt, source) }
            parent.copy(children = children)
        }
        else -> removed.updateItem(targetParentId) { parent ->
            parent.copy(branches = parent.branches.mapIndexed { index, branch ->
                if (index != targetBranch) branch else {
                    val children = branch.children.toMutableList().apply { add(insertAt, source) }
                    branch.copy(children = children)
                }
            })
        }
    }
}
