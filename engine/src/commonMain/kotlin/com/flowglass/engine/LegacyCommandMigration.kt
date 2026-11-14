package com.flowglass.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
private data class LegacyDocument(
    val version: Long,
    val autoCmds: List<LegacyCommand> = emptyList(),
    val oneTaps: List<LegacyCommand> = emptyList(),
    val logs: List<LegacyLog> = emptyList(),
    val running: Boolean = true,
    val mode: Long = 1,
)

@Serializable
private data class LegacyCommand(
    val id: Long,
    val auto: Boolean,
    val ic: String,
    val title: String,
    val desc: String,
    val triggers: List<LegacyItem> = emptyList(),
    val conditions: List<LegacyItem> = emptyList(),
    val actions: List<LegacyItem> = emptyList(),
    val category: Long = 0,
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val lastRun: String = "从未",
    val descriptionEdited: Boolean = true,
)

@Serializable
private data class LegacyItem(
    val id: Long,
    val name: String,
    val parameter: String = "",
    val ic: String,
)

@Serializable
private data class LegacyLog(
    val id: Long,
    val title: String,
    val reason: String,
    val time: String,
    val ok: Boolean,
    val ic: String,
    val detail: String = "",
    val steps: List<LegacyStep> = emptyList(),
)

@Serializable
private data class LegacyStep(
    val itemId: Long,
    val name: String,
    val status: String,
    val detail: String,
    val durationMs: Long,
)

object LegacyCommandMigration {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    fun migrate(text: String): RuleDocument {
        val source = try {
            json.decodeFromString<LegacyDocument>(text)
        } catch (error: Exception) {
            throw IllegalArgumentException("旧指令文件格式无效", error)
        }
        require(source.version in 1L..2L) { "不支持的旧存储版本 ${source.version}" }
        val commands = source.autoCmds + source.oneTaps
        val commandIds = commands.map { it.id }
        require(commandIds.all { it > 0L && it < Long.MAX_VALUE } && commandIds.distinct().size == commandIds.size) {
            "旧指令 id 必须为正数、小于 Long.MAX_VALUE 且不重复"
        }
        val itemIds = commands.flatMap { it.triggers + it.conditions + it.actions }.map { it.id }
        val commandIdSet = commandIds.toSet()
        require(itemIds.all { it > 0L && it < Long.MAX_VALUE && it !in commandIdSet } && itemIds.distinct().size == itemIds.size) {
            "旧条目 id 必须为正数、小于 Long.MAX_VALUE、不重复且不与指令 id 冲突"
        }
        val positiveLogIds = source.logs.map { it.id }.filter { it > 0L }
        val commandAndItemIds = (commandIds + itemIds).toSet()
        require(positiveLogIds.all { it < Long.MAX_VALUE && it !in commandAndItemIds } && positiveLogIds.distinct().size == positiveLogIds.size) {
            "旧执行记录 id 必须小于 Long.MAX_VALUE；正数 id 不得重复或与指令、条目 id 冲突"
        }
        val steps = source.logs.flatMap { it.steps }
        require(steps.all { it.itemId > 0L && it.itemId < Long.MAX_VALUE && it.durationMs >= 0L }) {
            "执行记录步骤标识或耗时无效"
        }
        // Released v0.4.x persisted negative seed log IDs. Only those log identities
        // are replaced; reserve even references to deleted items before allocating.
        var maximumId = (commandIds + itemIds + positiveLogIds + steps.map { it.itemId }).maxOrNull() ?: 0L
        val logs = source.logs.map { log ->
            val id = if (log.id > 0L) log.id else {
                require(maximumId < Long.MAX_VALUE - 1L) { "旧执行记录 id 分配失败：正数标识已用尽" }
                ++maximumId
            }
            log.toPersistedLog(id)
        }
        val migrateSeedAliases = source.version == 1L
        val converted = source.autoCmds.map { it.toRule(true, migrateSeedAliases) } +
            source.oneTaps.map { it.toRule(false, migrateSeedAliases) }
        require(source.mode in 0L..2L) { "运行模式无效" }
        return RuleDocument(
            formatVersion = CURRENT_FORMAT_VERSION,
            rules = converted.map { it.first },
            ui = RuleUiMetadata(
                running = source.running,
                mode = source.mode.toInt(),
                logs = logs,
                presentation = converted.associate { it.first.id to it.second },
            ),
        )
    }

    fun uuidForLegacyId(id: Long): String = legacyUuid(id)

    private fun LegacyCommand.toRule(auto: Boolean, migrateSeedAliases: Boolean): Pair<Rule, RulePresentation> {
        require(this.auto == auto) { "旧指令类型与所在列表不一致" }
        require(auto || (triggers.isEmpty() && conditions.isEmpty())) { "一键指令不能包含触发器或条件" }
        require(category in 0L..6L) { "指令分类无效" }
        val triggerParts = triggers.map { it.toComponent(ComponentKind.TRIGGER) }
        val conditionParts = conditions.map { it.toComponent(ComponentKind.CONDITION) }
        val actionParts = actions.map {
            it.toComponent(ComponentKind.ACTION, legacyCommandId = id.takeIf { migrateSeedAliases })
        }
        val rule = Rule(
            id = legacyUuid(id),
            name = title,
            description = desc,
            type = if (auto) RuleType.AUTO else RuleType.MANUAL,
            enabled = enabled,
            runMode = RunMode.DROP,
            triggerMatch = MatchSpec(mode = MatchMode.ANY),
            triggers = triggerParts.map { it.first },
            conditionMatch = MatchSpec(mode = MatchMode.ALL),
            conditions = conditionParts.map { it.first },
            actions = actionParts.map { it.first },
        )
        val presentation = RulePresentation(
                category = category.toInt(),
                icon = ic,
                pinned = pinned,
                lastRun = lastRun,
                descriptionEdited = descriptionEdited,
                legacyId = id,
                triggers = triggerParts.map { it.second },
                conditions = conditionParts.map { it.second },
                actions = actionParts.map { it.second },
        )
        return rule to presentation
    }

    private fun LegacyItem.toComponent(
        kind: ComponentKind,
        legacyCommandId: Long? = null,
    ): Pair<RuleComponent, ComponentPresentation> {
        val (displayName, displayParameter) = if (kind == ComponentKind.ACTION && legacyCommandId != null) {
            migrateKnownAlias(legacyCommandId, name, parameter)
        } else {
            name to parameter
        }
        val type = when (kind) {
            ComponentKind.TRIGGER -> triggerTypes[displayName]
            ComponentKind.CONDITION -> conditionTypes[displayName]
            ComponentKind.ACTION -> actionTypes[displayName]
        }
        val meta = BuiltInComponents.all.firstOrNull { it.typeId == type }
        val params = if (meta == null) null else try {
            ParameterCodec.parseComponent(meta, displayParameter)
        } catch (_: IllegalArgumentException) { null }
        val component = if (meta == null || params == null) {
            RuleComponent("legacy.unknown", mapOf(
                "raw" to JsonPrimitive("$name：$parameter"),
                "name" to JsonPrimitive(name), "parameter" to JsonPrimitive(parameter), "icon" to JsonPrimitive(ic),
            ))
        } else RuleComponent(meta.typeId, params)
        return component to ComponentPresentation(id, ic)
    }

    // v0.5 aliases are exact command/action pairs; the editable title is not identity.
    private fun migrateKnownAlias(commandId: Long, name: String, parameter: String): Pair<String, String> = when (commandId to name) {
        1L to "打开 WiFi" -> "开关 WiFi" to parameter.ifEmpty { "开启" }
        3L to "开启勿扰", 3L to "勿扰模式" -> "开关勿扰" to parameter.ifEmpty { "开启" }
        3L to "亮度降到 10%" -> "调节亮度" to parameter.ifEmpty { "10%" }
        4L to "关闭蓝牙" -> "开关蓝牙" to parameter.ifEmpty { "关闭" }
        101L to "亮度 30%" -> "调节亮度" to parameter.ifEmpty { "30%" }
        102L to "勿扰模式", 102L to "开启勿扰" -> "开关勿扰" to parameter.ifEmpty { "开启" }
        107L to "su -c 脚本" -> "执行 Shell(Root)" to parameter.ifEmpty { "echo 流光" }
        else -> name to parameter
    }

    private fun LegacyLog.toPersistedLog(id: Long) = PersistedLog(
        id = id,
        title = title,
        reason = reason,
        time = time,
        ok = ok,
        icon = ic,
        detail = detail,
        steps = steps.map { PersistedLogStep(it.itemId, it.name, it.status, it.detail, it.durationMs) },
    )

    // Stable UUID-shaped IDs preserve identity across repeated reads without retaining old numeric IDs in the schema.
    private fun legacyUuid(id: Long): String {
        val first = mix64(id.toULong() xor 0x7A9B4C13D2E68F05uL)
        val second = mix64(id.toULong() xor 0xC32F19A7E8645B0DuL)
        val raw = first.toString(16).padStart(16, '0') + second.toString(16).padStart(16, '0')
        val chars = raw.toCharArray()
        chars[12] = '4'
        chars[16] = "89ab"[(chars[16].digitToInt(16) and 0x3)]
        val hex = chars.concatToString()
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun mix64(input: ULong): ULong {
        var value = input + 0x9E3779B97F4A7C15uL
        value = (value xor (value shr 30)) * 0xBF58476D1CE4E5B9uL
        value = (value xor (value shr 27)) * 0x94D049BB133111EBuL
        return value xor (value shr 31)
    }

    private val actionTypes = mapOf(
        "开关 WiFi" to "action.wifi_switch",
        "开关蓝牙" to "action.bluetooth_switch",
        "开关勿扰" to "action.dnd_switch",
        "调节亮度" to "action.set_brightness",
        "调节音量" to "action.set_volume",
        "启动应用" to "action.launch_app",
        "发送通知" to "action.post_notification",
        "复制到剪贴板" to "action.copy_clipboard",
        "延时等待" to "action.delay",
        "执行 Shell(Root)" to "action.root_shell",
        "HTTP 请求" to "action.http_request",
    )

    private val triggerTypes = mapOf(
        "时间" to "trigger.time",
        "亮屏" to "trigger.screen_on",
        "熄屏" to "trigger.screen_off",
        "解锁" to "trigger.user_present",
        "插入充电器" to "trigger.power_connected",
        "拔出充电器" to "trigger.power_disconnected",
        "电量" to "trigger.battery_level",
        "进入地点" to "trigger.location_enter",
        "离开地点" to "trigger.location_exit",
        "收到通知" to "trigger.notification_received",
        "电量低于" to "trigger.battery_low",
        "充电状态" to "trigger.charging",
        "蓝牙连接" to "trigger.bluetooth_connected",
        "WiFi 连接/断开" to "trigger.wifi_changed",
        "应用启动" to "trigger.application_started",
        "开机完成" to "trigger.boot_completed",
    )

    private val conditionTypes = mapOf(
        "时间段" to "condition.time_range",
        "星期" to "condition.weekday",
        "电量范围" to "condition.battery_range",
        "充电状态" to "condition.charging",
        "网络类型" to "condition.network_type",
        "屏幕状态" to "condition.screen_state",
        "勿扰状态" to "condition.dnd_state",
    )
}
