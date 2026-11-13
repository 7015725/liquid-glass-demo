package com.flowglass.engine

import kotlinx.serialization.json.JsonPrimitive

object BuiltInComponents {
    private fun param(
        key: String,
        label: String,
        default: String = "",
        type: ParamType = ParamType.TEXT,
        required: Boolean = false,
        options: List<String> = emptyList(),
    ) = ParamSpec(key, label, type, required, JsonPrimitive(default), options)

    val triggers = listOf(
        ComponentMeta("trigger.time", "时间", "定时", ComponentKind.TRIGGER,
            listOf(param("time", "时间", "08:00", ParamType.TIME)), outputs = listOf("event.time")),
        ComponentMeta("trigger.location_enter", "进入地点", "位置", ComponentKind.TRIGGER,
            listOf(param("location", "地点", "公司")), outputs = listOf("event.location")),
        ComponentMeta("trigger.location_exit", "离开地点", "位置", ComponentKind.TRIGGER,
            listOf(param("location", "地点", "家")), outputs = listOf("event.location")),
        ComponentMeta("trigger.notification_received", "收到通知", "通知", ComponentKind.TRIGGER,
            listOf(param("text", "匹配文字", "验证码")), permissions = listOf("notification_listener")),
        ComponentMeta("trigger.battery_low", "电量低于", "设备", ComponentKind.TRIGGER,
            listOf(param("threshold", "电量", "20%", ParamType.PERCENTAGE))),
        ComponentMeta("trigger.charging", "充电状态", "设备", ComponentKind.TRIGGER,
            listOf(param("state", "状态", "开始充电", ParamType.ENUM, options = listOf("开始充电", "停止充电")))),
        ComponentMeta("trigger.bluetooth_connected", "蓝牙连接", "网络", ComponentKind.TRIGGER,
            listOf(param("name", "设备名称", "耳机")), permissions = listOf("bluetooth_connect")),
        ComponentMeta("trigger.wifi_changed", "WiFi 连接/断开", "网络", ComponentKind.TRIGGER,
            listOf(param("ssid", "网络名称", "连接家庭网络")), permissions = listOf("network_state")),
        ComponentMeta("trigger.application_started", "应用启动", "应用", ComponentKind.TRIGGER,
            listOf(param("package", "应用", "应用包名", ParamType.APPLICATION)), permissions = listOf("usage_access")),
        ComponentMeta("trigger.boot_completed", "开机完成", "设备", ComponentKind.TRIGGER,
            permissions = listOf("boot_completed")),
    )

    val conditions = listOf(
        ComponentMeta("condition.time_range", "时间段", "定时", ComponentKind.CONDITION,
            listOf(param("range", "时间范围", "08:00–22:00", ParamType.TIME))),
        ComponentMeta("condition.weekday", "星期", "定时", ComponentKind.CONDITION,
            listOf(param("days", "星期", "工作日", ParamType.ENUM, options = listOf("每天", "工作日", "周末")))),
        ComponentMeta("condition.battery_range", "电量范围", "设备", ComponentKind.CONDITION,
            listOf(param("range", "电量范围", "20%–80%", ParamType.PERCENTAGE))),
        ComponentMeta("condition.network_type", "网络类型", "网络", ComponentKind.CONDITION,
            listOf(param("network", "网络类型", "WiFi", ParamType.ENUM, options = listOf("WiFi", "移动网络", "无网络")))),
        ComponentMeta("condition.screen_state", "屏幕状态", "设备", ComponentKind.CONDITION,
            listOf(param("state", "屏幕状态", "已亮屏", ParamType.ENUM, options = listOf("已亮屏", "已熄屏")))),
        ComponentMeta("condition.dnd_state", "勿扰状态", "设备", ComponentKind.CONDITION,
            listOf(param("state", "勿扰状态", "已关闭", ParamType.ENUM, options = listOf("已开启", "已关闭")))),
    )

    val all: List<ComponentMeta> = triggers + conditions + BuiltInActions.metadata

    fun registry(): ComponentRegistry = ComponentRegistry().also { registry ->
        all.forEach(registry::register)
    }
}
