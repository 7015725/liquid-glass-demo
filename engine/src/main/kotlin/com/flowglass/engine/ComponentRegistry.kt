package com.flowglass.engine

import kotlinx.serialization.json.*

/** Authoring metadata, not a claim that a listener or privileged executor is installed. */
object ComponentRegistry {
    private fun text(key: String, label: String, default: String = "", required: Boolean = true, hint: String = "") =
        ParamSpec(key, label, "text", required, JsonPrimitive(default), hint = hint)
    private fun num(key: String, label: String, default: Long = 0) = ParamSpec(key, label, "long", defaultValue = JsonPrimitive(default))
    private fun decimal(key: String, label: String, default: Double = 0.0) = ParamSpec(key, label, "double", defaultValue = JsonPrimitive(default))
    private fun bool(key: String, label: String, default: Boolean = false, required: Boolean = true) = ParamSpec(key, label, "bool", required, JsonPrimitive(default))
    private fun choice(key: String, label: String, vararg choices: String, required: Boolean = true) =
        ParamSpec(key, label, "choice", required, JsonPrimitive(choices.first()), choices.toList())
    private fun value(key: String = "value", label: String = "值（JSON 或变量引用字符串）") = ParamSpec(key, label, "json", defaultValue = JsonNull)
    private fun code(key: String = "expression", label: String = "JavaScript 表达式") = ParamSpec(key, label, "code", hint = "仅编写，不执行；导入时完整展示并二次确认")
    private fun time(key: String, label: String, default: String) = ParamSpec(key, label, "time", defaultValue = JsonPrimitive(default), hint = "HH:mm，24小时制")
    private fun target() = text("target", "目标变量", hint = "local.name 或 global.name；event 与动作输出只读")
    private fun source() = text("source", "来源变量", hint = "local.name / global.name / event.name")
    private fun meta(kind: Kind, id: String, name: String, category: String, params: List<ParamSpec> = emptyList(),
        permissions: List<String> = emptyList(), tier: String = "NORMAL", outputs: List<String> = emptyList(), description: String = name) =
        ComponentMeta("${kind.name.lowercase()}.$id", name, category, kind, params, permissions, tier, outputs,
            "$description。仅声明编写能力；事件监听与执行端未实现。")
    private fun trigger(id: String, name: String, category: String, params: List<ParamSpec> = emptyList(), permissions: List<String> = emptyList(), tier: String = "NORMAL", outputs: List<String> = emptyList()) = meta(Kind.TRIGGER, id, name, category, params, permissions, tier, outputs)
    private fun condition(id: String, name: String, category: String, params: List<ParamSpec>, permissions: List<String> = emptyList()) = meta(Kind.CONDITION, id, name, category, params, permissions)
    private fun action(id: String, name: String, category: String, params: List<ParamSpec> = emptyList(), permissions: List<String> = emptyList(), outputs: List<String> = emptyList(), description: String = name) = meta(Kind.ACTION, id, name, category, params, permissions, outputs = outputs, description = description)
    private val wifiPermissions = listOf("android.permission.ACCESS_WIFI_STATE", "android.permission.ACCESS_FINE_LOCATION", "android.permission.NEARBY_WIFI_DEVICES")
    private val accessibility = listOf("android.permission.BIND_ACCESSIBILITY_SERVICE")
    private val notificationAccess = listOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")
    private val compare = choice("op", "比较方式", "eq", "ne", "gt", "gte", "lt", "lte", "contains", "empty", "list_contains", "regex")
    private val battery = listOf(choice("op", "比较方式", "lt", "lte", "eq", "gte", "gt"), num("level", "电量百分比（0–100）", 20))

    val items: List<ComponentMeta> = listOf(
        trigger("wifi_connected", "连接 WiFi", "网络", listOf(text("ssid", "WiFi 名称")), wifiPermissions, outputs = listOf("event.ssid", "event.time")),
        trigger("wifi_disconnected", "断开 WiFi", "网络", listOf(text("ssid", "WiFi 名称", required = false)), wifiPermissions, outputs = listOf("event.ssid")),
        trigger("bluetooth_connected", "连接蓝牙设备", "网络", listOf(text("name", "设备名称")), listOf("android.permission.BLUETOOTH_CONNECT"), outputs = listOf("event.name", "event.address")),
        trigger("network_changed", "网络切换", "网络", listOf(choice("network", "网络类型", "any", "wifi", "cellular", "none")), listOf("android.permission.ACCESS_NETWORK_STATE"), outputs = listOf("event.network")),
        trigger("notification", "收到通知", "应用", listOf(text("package", "应用包名", required = false), text("contains", "包含文字", required = false)), notificationAccess, outputs = listOf("event.title", "event.text", "event.package")),
        trigger("notification_removed", "通知移除", "应用", listOf(text("package", "应用包名", required = false)), notificationAccess, outputs = listOf("event.title", "event.package")),
        trigger("time", "每日定时", "时间", listOf(time("time", "时间", "08:00")), listOf("android.permission.SCHEDULE_EXACT_ALARM"), outputs = listOf("event.time")),
        trigger("interval", "周期定时", "时间", listOf(num("seconds", "间隔秒数", 60)), listOf("android.permission.SCHEDULE_EXACT_ALARM"), outputs = listOf("event.time")),
        trigger("sun_event", "日出日落", "时间", listOf(choice("event", "事件", "sunrise", "sunset"), decimal("latitude", "纬度"), decimal("longitude", "经度"), num("offsetMinutes", "偏移分钟")), listOf("android.permission.SCHEDULE_EXACT_ALARM")),
        trigger("charging", "充电状态变化", "设备", listOf(bool("charging", "开始充电", true)), outputs = listOf("event.charging")),
        trigger("screen", "屏幕状态", "设备", listOf(choice("state", "事件", "on", "off", "unlocked")), outputs = listOf("event.state")),
        trigger("battery", "电量阈值", "设备", battery, outputs = listOf("event.battery")),
        trigger("headphones", "耳机插拔", "设备", listOf(bool("connected", "插入", true)), outputs = listOf("event.connected")),
        trigger("app_opened", "应用打开", "应用", listOf(text("package", "应用包名")), accessibility, outputs = listOf("event.package")),
        trigger("app_closed", "应用关闭", "应用", listOf(text("package", "应用包名")), accessibility, outputs = listOf("event.package")),
        trigger("ui_text", "界面出现文字", "应用", listOf(text("text", "文字"), text("package", "应用包名", required = false)), accessibility, outputs = listOf("event.text", "event.package")),
        trigger("global_variable_changed", "全局变量变化", "变量", listOf(text("name", "全局变量名", hint = "global.name")), outputs = listOf("event.name", "event.oldValue", "event.newValue")),
        trigger("setting_changed", "系统设置变化", "设备", listOf(choice("namespace", "设置表", "system", "secure", "global"), text("key", "设置键")), outputs = listOf("event.value")),
        trigger("clipboard", "剪贴板变化", "设备", permissions = accessibility, outputs = listOf("event.text")),
        trigger("shake", "摇一摇", "传感器", listOf(decimal("threshold", "加速度阈值", 15.0)), outputs = listOf("event.acceleration")),
        trigger("location", "进入或离开地点", "位置", listOf(choice("event", "事件", "enter", "exit"), decimal("latitude", "纬度"), decimal("longitude", "经度"), num("radiusMeters", "半径米数", 100)), listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION")),
        trigger("key", "物理按键组合", "特权", listOf(text("keys", "按键码组合")), listOf("ROOT"), "ROOT", listOf("event.keys")),
        trigger("log", "日志关键字", "特权", listOf(text("contains", "关键字")), listOf("ROOT", "android.permission.READ_LOGS"), "ROOT", listOf("event.line")),
        trigger("file_changed", "文件变化", "特权", listOf(text("path", "文件路径")), listOf("ROOT"), "ROOT", listOf("event.path")),
        trigger("foreground_changed", "前台应用变化（Hook）", "特权", permissions = listOf("XPOSED"), tier = "XPOSED", outputs = listOf("event.package")),
        trigger("app_installed", "应用安装（Hook）", "特权", listOf(text("package", "应用包名", required = false)), listOf("XPOSED"), "XPOSED", listOf("event.package")),
        trigger("permission_prompt", "权限弹窗（Hook）", "特权", permissions = listOf("XPOSED"), tier = "XPOSED", outputs = listOf("event.package", "event.permission")),
        condition("time_range", "时间范围", "时间", listOf(time("from", "开始时间", "18:00"), time("to", "结束时间", "23:00"))),
        condition("weekday", "星期", "时间", listOf(choice("day", "星期", "1", "2", "3", "4", "5", "6", "7"))),
        condition("variable", "变量比较", "变量", listOf(text("name", "变量引用", hint = "global.mode / local.count / event.title"), compare, value())),
        condition("wifi", "当前 WiFi", "网络", listOf(text("ssid", "WiFi 名称")), wifiPermissions),
        condition("charging", "当前充电状态", "设备", listOf(bool("charging", "正在充电", true))),
        condition("battery", "当前电量", "设备", battery),
        condition("app_foreground", "当前前台应用", "应用", listOf(text("package", "应用包名")), accessibility),
        condition("expression", "表达式条件", "脚本", listOf(code())),
        action("notification", "发送通知", "应用", listOf(text("title", "标题"), text("text", "正文", required = false)), listOf("android.permission.POST_NOTIFICATIONS")),
        action("open_app", "打开应用", "应用", listOf(text("package", "应用包名")), listOf("android.permission.QUERY_ALL_PACKAGES")),
        action("set_ringer", "铃声模式", "设备", listOf(choice("mode", "模式", "normal", "vibrate", "silent")), listOf("android.permission.MODIFY_AUDIO_SETTINGS", "android.permission.ACCESS_NOTIFICATION_POLICY")),
        action("shell", "Shell 脚本", "脚本", listOf(code("cmd", "完整 Shell 命令"), bool("root", "需要 Root", required = false), choice("backend", "执行后端", "auto", "normal", "shizuku", "root", required = false)), outputs = listOf("shell.stdout", "shell.stderr", "shell.exitCode"), description = "Shell 可选择普通 / Shizuku / Root；需按 root 与 backend 参数检查额外授权，不代表已授权"),
        action("javascript", "JavaScript 脚本", "脚本", listOf(code("code", "完整 JavaScript"), choice("backend", "执行位置", "app", "root", required = false)), outputs = listOf("javascript.result")),
        action("variable_set", "变量赋值", "变量", listOf(target(), value())),
        action("variable_calculate", "变量计算", "变量", listOf(target(), code())),
        action("variable_convert", "变量类型转换", "变量", listOf(target(), choice("type", "目标类型", "string", "int", "decimal", "bool", "list", "dict", "null"))),
        action("variable_delete", "删除变量", "变量", listOf(target())),
        action("list_append", "列表追加", "列表", listOf(target(), value())),
        action("list_insert", "列表插入", "列表", listOf(target(), num("index", "位置（从0开始）"), value())),
        action("list_remove", "列表移除", "列表", listOf(target(), num("index", "位置（从0开始）"))),
        action("list_get", "读取列表元素", "列表", listOf(source(), num("index", "位置（从0开始）")), outputs = listOf("list.value")),
        action("list_length", "列表长度", "列表", listOf(source()), outputs = listOf("list.length")),
        action("list_find", "列表查找", "列表", listOf(source(), value()), outputs = listOf("list.index")),
        action("list_sort", "列表排序", "列表", listOf(target(), choice("order", "顺序", "asc", "desc"))),
        action("list_foreach", "列表遍历", "流程", listOf(source()), outputs = listOf("loop.index", "loop.value"), description = "对每个列表元素执行 children；loop.index 与 loop.value 只读"),
        action("dict_set", "设置字典键", "字典", listOf(target(), text("key", "键"), value())),
        action("dict_get", "读取字典键", "字典", listOf(source(), text("key", "键")), outputs = listOf("dict.value")),
        action("dict_delete", "删除字典键", "字典", listOf(target(), text("key", "键"))),
        action("if", "如果 / 否则", "流程", listOf(code()), description = "expression 为 JS 条件；children 为 then，elseActions 为 else"),
        action("loop", "重复循环", "流程", listOf(num("count", "次数（正整数）", 1)), outputs = listOf("loop.index"), description = "按 count 次执行 children"),
        action("delay", "延时", "流程", listOf(num("millis", "毫秒", 1000))),
        action("wait_event", "等待事件", "流程", listOf(text("eventType", "触发器 typeId", "trigger.notification")), outputs = listOf("event.type", "event.data"), description = "等待指定事件，受动作 timeoutSeconds 限制"),
        action("stop", "停止任务", "流程"),
        action("call_manual", "调用一键指令", "流程", listOf(text("ruleId", "一键指令 ID")), description = "以规则 ID 引用一键指令；导入时重映射同批次目标")
    )
    private val byType = items.associateBy { it.typeId }
    fun find(typeId: String): ComponentMeta? = byType[typeId]
    fun newComponent(typeId: String): Component {
        val meta = requireNotNull(find(typeId)) { "未知组件类型：$typeId" }
        return Component(type = typeId, params = meta.params.associate { it.key to it.defaultValue })
    }
}
