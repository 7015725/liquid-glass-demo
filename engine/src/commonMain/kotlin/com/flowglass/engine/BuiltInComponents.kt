package com.flowglass.engine

import kotlinx.serialization.json.JsonPrimitive

object BuiltInComponents {
    private fun param(
        key: String,
        label: String,
        default: String = "",
        type: ParamType = ParamType.TEXT,
        required: Boolean = false,
        options: List<ParamOption> = emptyList(),
        hint: String = "",
    ) = ParamSpec(key, label, type, required, when (type) {
        ParamType.PERCENTAGE -> JsonPrimitive(default.removeSuffix("%").toInt())
        ParamType.TIME_RANGE -> kotlinx.serialization.json.buildJsonObject {
            put("from", JsonPrimitive("08:00")); put("to", JsonPrimitive("22:00"))
        }
        ParamType.INT_RANGE -> kotlinx.serialization.json.buildJsonObject {
            put("min", JsonPrimitive(20)); put("max", JsonPrimitive(80))
        }
        else -> JsonPrimitive(default)
    }, options, hint = hint,
        minValue = if (type == ParamType.INT_RANGE) 0 else null,
        maxValue = if (type == ParamType.INT_RANGE) 100 else null,
        suffix = if (type == ParamType.INT_RANGE) "%" else "")

    private val allWeekdays = "1,2,3,4,5,6,7"

    val triggers = listOf(
        ComponentMeta("trigger.time", "时间", "定时", ComponentKind.TRIGGER,
            listOf(
                param("time", "时间", "08:00", ParamType.TIME, required = true),
                param("days", "重复", allWeekdays, ParamType.WEEKDAYS, required = true,
                    hint = "可选每天、工作日、周末，或点选具体星期。"),
            ), outputs = listOf("event.time")),
        ComponentMeta("trigger.screen_on", "亮屏", "设备", ComponentKind.TRIGGER),
        ComponentMeta("trigger.screen_off", "熄屏", "设备", ComponentKind.TRIGGER),
        ComponentMeta("trigger.user_present", "解锁", "设备", ComponentKind.TRIGGER),
        ComponentMeta("trigger.power_connected", "插入充电器", "设备", ComponentKind.TRIGGER),
        ComponentMeta("trigger.power_disconnected", "拔出充电器", "设备", ComponentKind.TRIGGER),
        ComponentMeta("trigger.battery_level", "电量", "设备", ComponentKind.TRIGGER,
            listOf(
                param("direction", "方向", "below", ParamType.ENUM, required = true,
                    options = listOf(ParamOption("below", "低于"), ParamOption("above", "高于"))),
                param("threshold", "阈值", "20%", ParamType.PERCENTAGE, required = true),
            ), outputs = listOf("event.battery")),
        ComponentMeta("trigger.boot_completed", "开机完成", "设备", ComponentKind.TRIGGER),

        // Kept for rules saved by earlier builds. Hidden from the add panel; the runtime maps
        // them onto the new battery / power sources so existing rules keep working.
        ComponentMeta("trigger.battery_low", "电量低于", "设备", ComponentKind.TRIGGER,
            listOf(param("threshold", "电量", "20%", ParamType.PERCENTAGE))),
        ComponentMeta("trigger.charging", "充电状态", "设备", ComponentKind.TRIGGER,
            listOf(param("state", "状态", "charging", ParamType.ENUM, options = listOf(
                ParamOption("charging", "开始充电"), ParamOption("stopped", "停止充电"))))),
        ComponentMeta("trigger.wifi", "WiFi 连接/断开", "网络", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "connected", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("connected", "已连接"), ParamOption("disconnected", "已断开"))),
                param("ssid", "网络名称", "", ParamType.TEXT, required = false,
                    hint = "留空匹配任意 WiFi。读取网络名称需要定位权限。"),
            ), permissions = listOf("location")),
        ComponentMeta("trigger.bluetooth", "蓝牙连接/断开", "网络", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "connected", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("connected", "已连接"), ParamOption("disconnected", "已断开"))),
                param("name", "设备名称", "", ParamType.TEXT, required = false,
                    hint = "留空匹配任意设备；填写后按名称包含匹配。"),
            ), permissions = listOf("bluetooth_connect")),
        ComponentMeta("trigger.notification", "收到通知", "通知", ComponentKind.TRIGGER,
            listOf(
                param("app", "应用", "", ParamType.APPLICATION, required = false,
                    hint = "留空匹配任意应用。"),
                param("title", "标题关键字", "", ParamType.TEXT, required = false),
                param("text", "内容关键字", "", ParamType.TEXT, required = false),
            ), permissions = listOf("notification_listener")),
        ComponentMeta("trigger.app_foreground", "应用前台/离开", "应用", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "foreground", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("foreground", "切到前台"), ParamOption("background", "离开前台"))),
                param("package", "应用", "应用包名", ParamType.APPLICATION, required = true),
            ), permissions = listOf("foreground_app")),
        ComponentMeta("trigger.headset", "耳机插入/拔出", "设备", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "plugged", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("plugged", "已插入"), ParamOption("unplugged", "已拔出"))),
                param("kind", "类型", "any", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("any", "任意耳机"), ParamOption("wired", "有线耳机"),
                    ParamOption("bluetooth", "蓝牙音频设备"))),
            )),
        ComponentMeta("trigger.location_enter", "进入地点", "位置", ComponentKind.TRIGGER,
            listOf(param("location", "地点", "公司")), comingSoon = true),
        ComponentMeta("trigger.location_exit", "离开地点", "位置", ComponentKind.TRIGGER,
            listOf(param("location", "地点", "家")), comingSoon = true),
    )

    val conditions = listOf(
        ComponentMeta("condition.time_range", "时间段", "定时", ComponentKind.CONDITION,
            listOf(param("range", "时间范围", "08:00–22:00", ParamType.TIME_RANGE))),
        ComponentMeta("condition.weekday", "星期", "定时", ComponentKind.CONDITION,
            listOf(param("days", "星期", "workday", ParamType.ENUM, options = listOf(
                ParamOption("everyday", "每天"), ParamOption("workday", "工作日"),
                ParamOption("weekend", "周末"))))),
        ComponentMeta("condition.battery_range", "电量范围", "设备", ComponentKind.CONDITION,
            // A range keeps both numeric endpoints and uses the range text editor.
            listOf(param("range", "电量范围", "20%–80%", ParamType.INT_RANGE))),
        ComponentMeta("condition.charging", "充电状态", "设备", ComponentKind.CONDITION,
            listOf(param("state", "状态", "charging", ParamType.ENUM, options = listOf(
                ParamOption("charging", "充电中"), ParamOption("not_charging", "未充电"))))),
        ComponentMeta("condition.screen_state", "屏幕状态", "设备", ComponentKind.CONDITION,
            listOf(param("state", "屏幕状态", "on", ParamType.ENUM, options = listOf(
                ParamOption("on", "已亮屏"), ParamOption("off", "已熄屏"))))),
        ComponentMeta("condition.network_type", "网络类型", "网络", ComponentKind.CONDITION,
            listOf(param("network", "网络类型", "wifi", ParamType.ENUM, options = listOf(
                ParamOption("wifi", "WiFi"), ParamOption("mobile", "移动网络"),
                ParamOption("none", "无网络")))), comingSoon = true),
        ComponentMeta("condition.dnd_state", "勿扰状态", "设备", ComponentKind.CONDITION,
            listOf(param("state", "勿扰状态", "off", ParamType.ENUM, options = listOf(
                ParamOption("on", "已开启"), ParamOption("off", "已关闭")))), comingSoon = true),
    )

    val all: List<ComponentMeta> = triggers + conditions + BuiltInActions.metadata

    fun registry(): ComponentRegistry = ComponentRegistry().also { registry ->
        all.forEach(registry::register)
    }
}
