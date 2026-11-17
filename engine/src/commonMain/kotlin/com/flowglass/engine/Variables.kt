package com.flowglass.engine

/** Which table a user-visible variable belongs to. */
enum class VariableScope { BUILTIN, LOCAL, GLOBAL }

/** Storage/editor type of a variable. V1 keeps this advisory; values are not forced at runtime. */
enum class VariableType { TEXT, NUMBER, BOOLEAN, LIST }

/** One built-in or event variable described for insertion and the management page. */
data class VariableSpec(
    val name: String,
    val scope: VariableScope,
    val type: VariableType,
    val description: String,
    /** Legacy `%{...}` key this built-in replaces, when there is one. */
    val legacyKey: String? = null,
    /** Trigger kind that produces this event variable; empty for run/action built-ins. */
    val source: String = "",
)

/** A variable created or type-changed during a run; the host persists its metadata. */
data class VariableDefinition(
    val name: String,
    val scope: VariableScope,
    val type: VariableType,
    val resetEachRun: Boolean = false,
    val initial: Any? = null,
)

/**
 * Naming rules for user-defined variables. These are deliberately independent from the legacy
 * `%{...}` scanner so old names keep working while the new UI only offers `{名字}`.
 */
object VariableName {
    const val MAX_LENGTH = 64

    fun normalize(raw: String): String = raw.trim()

    fun isValid(raw: String): Boolean {
        val name = normalize(raw)
        if (name.length !in 1..MAX_LENGTH) return false
        val first = name[0]
        if (!first.isLetter() && first != '_') return false
        for (char in name) {
            if (!char.isLetterOrDigit() && char != '_' && char != '-') return false
        }
        return true
    }

    fun error(raw: String): String? = when {
        normalize(raw).isEmpty() -> "名字不能为空"
        !isValid(raw) -> "名字只能用中文、字母、数字、_ 或 -，不能有空格和点，长度 1–64"
        else -> null
    }

    fun token(name: String): String = "{$name}"
}

/**
 * Built-in variable catalogue. The Chinese names are the only names shown by the new UI. Legacy
 * keys remain recognised by the parser and by existing documents.
 */
object BuiltInVariables {
    val all: List<VariableSpec> = listOf(
        VariableSpec("上一步结果", VariableScope.BUILTIN, VariableType.TEXT,
            "上一步执行的结果摘要", legacyKey = "last"),
        VariableSpec("Shell输出", VariableScope.BUILTIN, VariableType.TEXT,
            "Root Shell 的标准输出", legacyKey = "shell.stdout"),
        VariableSpec("HTTP响应", VariableScope.BUILTIN, VariableType.TEXT,
            "HTTP 请求的响应正文", legacyKey = "http.body"),
        VariableSpec("脚本结果", VariableScope.BUILTIN, VariableType.TEXT,
            "脚本动作的返回值", legacyKey = "script.result"),
        VariableSpec("触发时间", VariableScope.BUILTIN, VariableType.TEXT,
            "本次触发的时间（HH:mm）", legacyKey = "event.time", source = "event"),
        VariableSpec("电池电量", VariableScope.BUILTIN, VariableType.NUMBER,
            "触发时的电池电量（0–100）", legacyKey = "event.battery", source = "event"),
        VariableSpec("WiFi名称", VariableScope.BUILTIN, VariableType.TEXT,
            "当前连接的 WiFi 名称", source = "wifi"),
        VariableSpec("WiFi状态", VariableScope.BUILTIN, VariableType.TEXT,
            "WiFi 连接状态", source = "wifi"),
        VariableSpec("蓝牙名称", VariableScope.BUILTIN, VariableType.TEXT,
            "当前蓝牙设备名称", source = "bluetooth"),
        VariableSpec("蓝牙状态", VariableScope.BUILTIN, VariableType.TEXT,
            "蓝牙连接状态", source = "bluetooth"),
        VariableSpec("耳机名称", VariableScope.BUILTIN, VariableType.TEXT,
            "耳机或蓝牙音频设备名称", source = "headset"),
        VariableSpec("耳机类型", VariableScope.BUILTIN, VariableType.TEXT,
            "有线或蓝牙耳机", source = "headset"),
        VariableSpec("耳机状态", VariableScope.BUILTIN, VariableType.TEXT,
            "耳机插入或拔出", source = "headset"),
        VariableSpec("通知应用", VariableScope.BUILTIN, VariableType.TEXT,
            "发出通知的应用包名；分身会带 @用户", source = "notification"),
        VariableSpec("通知用户", VariableScope.BUILTIN, VariableType.NUMBER,
            "通知所属的 Android 用户 ID", source = "notification"),
        VariableSpec("通知标题", VariableScope.BUILTIN, VariableType.TEXT,
            "收到的通知标题", source = "notification"),
        VariableSpec("通知内容", VariableScope.BUILTIN, VariableType.TEXT,
            "收到的通知正文", source = "notification"),
        VariableSpec("应用包名", VariableScope.BUILTIN, VariableType.TEXT,
            "事件所属应用包名；分身会带 @用户", source = "app"),
        VariableSpec("应用用户", VariableScope.BUILTIN, VariableType.NUMBER,
            "事件所属的 Android 用户 ID", source = "app"),
        VariableSpec("纬度", VariableScope.BUILTIN, VariableType.NUMBER,
            "位置事件的纬度", source = "location"),
        VariableSpec("经度", VariableScope.BUILTIN, VariableType.NUMBER,
            "位置事件的经度", source = "location"),
        VariableSpec("系统事件", VariableScope.BUILTIN, VariableType.TEXT,
            "系统钩子事件的类型", source = "system"),
    )

    val byName: Map<String, VariableSpec> = all.associateBy { it.name }
    val byLegacyKey: Map<String, VariableSpec> = all.mapNotNull { spec -> spec.legacyKey?.let { it to spec } }.toMap()

    fun outputName(legacyKey: String): String? = byLegacyKey[legacyKey]?.name

    fun specsForSource(source: String): List<VariableSpec> =
        all.filter { it.source == source || it.source.isEmpty() }

    /**
     * New-style values for one trigger event. Legacy keys are still emitted by the dispatcher;
     * this function only adds the Chinese names consumed by `{名字}`.
     */
    fun eventValues(kind: String, data: Map<String, String>, localTime: String): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>("触发时间" to localTime)
        data["battery"]?.toIntOrNull()?.let { values["电池电量"] = it }
        when (kind) {
            "wifi" -> {
                data["ssid"]?.let { values["WiFi名称"] = it }
                data["state"]?.let { values["WiFi状态"] = it }
            }
            "bluetooth" -> {
                data["name"]?.let { values["蓝牙名称"] = it }
                data["state"]?.let { values["蓝牙状态"] = it }
            }
            "headset" -> {
                data["name"]?.let { values["耳机名称"] = it }
                data["kind"]?.let { values["耳机类型"] = it }
                data["state"]?.let { values["耳机状态"] = it }
            }
            "notification" -> {
                data["notification.app"]?.let { values["通知应用"] = it }
                data["notification.user"]?.toIntOrNull()?.let { values["通知用户"] = it }
                data["notification.title"]?.let { values["通知标题"] = it }
                data["notification.text"]?.let { values["通知内容"] = it }
            }
            "app_foreground", "app_process_started", "app_process_ended",
            "app_crash", "app_anr", "media_playing", "media_paused", "media_stopped",
            "foreground_service_started", "foreground_service_stopped" -> {
                data["app"]?.let { values["应用包名"] = it }
                data["app.user"]?.toIntOrNull()?.let { values["应用用户"] = it }
            }
            "location" -> {
                data["lat"]?.let { values["纬度"] = it }
                data["lng"]?.let { values["经度"] = it }
            }
        }
        data["event"]?.let { values["系统事件"] = it }
        return values
    }
}
