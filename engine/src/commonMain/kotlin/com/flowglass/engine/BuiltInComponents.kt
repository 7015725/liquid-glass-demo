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
        source: ParamSource = ParamSource.NONE,
        applicationScope: ApplicationScope = ApplicationScope.ALL_PROFILES,
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
        suffix = if (type == ParamType.INT_RANGE) "%" else "",
        source = source,
        applicationScope = applicationScope)

    private val allWeekdays = "1,2,3,4,5,6,7"

    /** Declarations for components executed entirely inside the app process. */
    private val normalLayer = listOf(LayerSupport(ExecutionLayer.NORMAL))
    private fun normalSupport(vararg permissions: String) =
        listOf(LayerSupport(ExecutionLayer.NORMAL, permissions.toList()))

    val triggers = listOf(
        ComponentMeta("trigger.time", "时间", "定时", ComponentKind.TRIGGER,
            listOf(
                param("time", "时间", "08:00", ParamType.TIME, required = true),
                param("days", "重复", allWeekdays, ParamType.WEEKDAYS, required = true,
                    hint = "可选每天、工作日、周末，或点选具体星期。"),
            ), supports = normalLayer, outputs = listOf("event.time")),
        ComponentMeta("trigger.screen_on", "亮屏", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.screen_off", "熄屏", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.user_present", "解锁", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.power_connected", "插入充电器", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.power_disconnected", "拔出充电器", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.battery_level", "电量", "设备", ComponentKind.TRIGGER,
            listOf(
                param("direction", "方向", "below", ParamType.ENUM, required = true,
                    options = listOf(ParamOption("below", "低于"), ParamOption("above", "高于"))),
                param("threshold", "阈值", "20%", ParamType.PERCENTAGE, required = true),
            ), supports = normalLayer, outputs = listOf("event.battery")),
        ComponentMeta("trigger.boot_completed", "开机完成", "设备", ComponentKind.TRIGGER, supports = normalLayer),

        // Kept for rules saved by earlier builds. Hidden from the add panel; the runtime maps
        // them onto the new battery / power sources so existing rules keep working.
        ComponentMeta("trigger.battery_low", "电量低于", "设备", ComponentKind.TRIGGER,
            listOf(param("threshold", "电量", "20%", ParamType.PERCENTAGE)), supports = normalLayer),
        ComponentMeta("trigger.charging", "充电状态", "设备", ComponentKind.TRIGGER,
            listOf(param("state", "状态", "charging", ParamType.ENUM, options = listOf(
                ParamOption("charging", "开始充电"), ParamOption("stopped", "停止充电")))),
            supports = normalLayer),
        ComponentMeta("trigger.wifi", "WiFi 连接/断开", "网络", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "connected", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("connected", "已连接"), ParamOption("disconnected", "已断开"))),
                param("ssid", "网络名称", "", ParamType.TEXT, required = false,
                    hint = "留空匹配任意 WiFi。读取网络名称需要定位权限。",
                    source = ParamSource.WIFI_NETWORKS),
            ), permissions = listOf("location"), supports = normalSupport("location")),
        ComponentMeta("trigger.bluetooth", "蓝牙连接/断开", "网络", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "connected", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("connected", "已连接"), ParamOption("disconnected", "已断开"))),
                param("name", "设备名称", "", ParamType.TEXT, required = false,
                    hint = "留空匹配任意设备；填写后按名称包含匹配。",
                    source = ParamSource.PAIRED_BLUETOOTH),
            ), permissions = listOf("bluetooth_connect"), supports = normalSupport("bluetooth_connect")),
        ComponentMeta("trigger.notification", "收到通知", "通知", ComponentKind.TRIGGER,
            listOf(
                param("app", "应用", "", ParamType.APPLICATION, required = false,
                    hint = "留空匹配任意应用。"),
                param("title", "标题关键字", "", ParamType.TEXT, required = false),
                param("text", "内容关键字", "", ParamType.TEXT, required = false),
            ), permissions = listOf("notification_listener"), supports = normalSupport("notification_listener")),
        ComponentMeta("trigger.app_foreground", "应用前台/离开", "应用", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "foreground", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("foreground", "切到前台"), ParamOption("background", "离开前台"))),
                param("package", "应用", "应用包名", ParamType.APPLICATION, required = true,
                    hint = "只列出当前资料的主应用；应用前台触发器暂不能可靠识别分身。",
                    applicationScope = ApplicationScope.CURRENT_PROFILE),
            ), permissions = listOf("foreground_app"), supports = normalSupport("foreground_app"),
            applicationCapability = ApplicationCapability.FOREGROUND_EVENTS),
        ComponentMeta("trigger.headset", "耳机插入/拔出", "设备", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "plugged", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("plugged", "已插入"), ParamOption("unplugged", "已拔出"))),
                param("kind", "类型", "any", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("any", "任意耳机"), ParamOption("wired", "有线耳机"),
                    ParamOption("bluetooth", "蓝牙音频设备"))),
            ), supports = normalLayer),
        ComponentMeta("trigger.location_enter", "进入地点", "位置", ComponentKind.TRIGGER,
            listOf(
                ParamSpec("location", "地点", ParamType.TEXT, defaultValue = JsonPrimitive(""),
                    hint = "纬度,经度，例如 31.2304,121.4737",
                    editor = ParamEditor.LOCATION, source = ParamSource.CURRENT_LOCATION),
                ParamSpec("radius", "半径", ParamType.INTEGER, defaultValue = JsonPrimitive(500),
                    hint = "100–5000 米", minValue = 100, maxValue = 5000),
            ), permissions = listOf("location"), supports = normalSupport("location")),
        ComponentMeta("trigger.location_exit", "离开地点", "位置", ComponentKind.TRIGGER,
            listOf(
                ParamSpec("location", "地点", ParamType.TEXT, defaultValue = JsonPrimitive(""),
                    hint = "纬度,经度，例如 31.2304,121.4737",
                    editor = ParamEditor.LOCATION, source = ParamSource.CURRENT_LOCATION),
                ParamSpec("radius", "半径", ParamType.INTEGER, defaultValue = JsonPrimitive(500),
                    hint = "100–5000 米", minValue = 100, maxValue = 5000),
            ), permissions = listOf("location"), supports = normalSupport("location")),
    )

    val conditions = listOf(
        ComponentMeta("condition.time_range", "时间段", "定时", ComponentKind.CONDITION,
            listOf(param("range", "时间范围", "08:00–22:00", ParamType.TIME_RANGE)), supports = normalLayer),
        ComponentMeta("condition.weekday", "星期", "定时", ComponentKind.CONDITION,
            listOf(param("days", "星期", "workday", ParamType.ENUM, options = listOf(
                ParamOption("everyday", "每天"), ParamOption("workday", "工作日"),
                ParamOption("weekend", "周末")))), supports = normalLayer),
        ComponentMeta("condition.battery_range", "电量范围", "设备", ComponentKind.CONDITION,
            // A range keeps both numeric endpoints and uses the range text editor.
            listOf(param("range", "电量范围", "20%–80%", ParamType.INT_RANGE)), supports = normalLayer),
        ComponentMeta("condition.charging", "充电状态", "设备", ComponentKind.CONDITION,
            listOf(param("state", "状态", "charging", ParamType.ENUM, options = listOf(
                ParamOption("charging", "充电中"), ParamOption("not_charging", "未充电")))), supports = normalLayer),
        ComponentMeta("condition.screen_state", "屏幕状态", "设备", ComponentKind.CONDITION,
            listOf(param("state", "屏幕状态", "on", ParamType.ENUM, options = listOf(
                ParamOption("on", "已亮屏"), ParamOption("off", "已熄屏")))), supports = normalLayer),
        ComponentMeta("condition.network_type", "网络类型", "网络", ComponentKind.CONDITION,
            listOf(param("network", "网络类型", "wifi", ParamType.ENUM, options = listOf(
                ParamOption("wifi", "WiFi"), ParamOption("mobile", "移动网络"),
                ParamOption("none", "无网络")))), supports = normalLayer),
        ComponentMeta("condition.dnd_state", "勿扰状态", "设备", ComponentKind.CONDITION,
            listOf(param("state", "勿扰状态", "off", ParamType.ENUM, options = listOf(
                ParamOption("on", "已开启"), ParamOption("off", "已关闭")))),
            permissions = listOf("dnd"), supports = normalSupport("dnd")),
        ComponentMeta("condition.var_compare", "变量比较", "流程控制", ComponentKind.CONDITION,
            listOf(
                param("left", "左侧", "%{run.x}", ParamType.TEXT, required = true,
                    hint = "支持 %{变量} 或字面量。"),
                param("op", "比较", "eq", ParamType.ENUM, options = listOf(
                    ParamOption("eq", "等于"), ParamOption("ne", "不等于"),
                    ParamOption("gt", "大于"), ParamOption("ge", "大于等于"),
                    ParamOption("lt", "小于"), ParamOption("le", "小于等于"),
                    ParamOption("contains", "包含"))),
                param("right", "右侧", "", ParamType.TEXT),
            ), supports = normalLayer),
    )

    val all: List<ComponentMeta> = triggers + conditions + BuiltInActions.metadata + BuiltInBlocks.metadata

    fun registry(): ComponentRegistry = ComponentRegistry().also { registry ->
        all.forEach(registry::register)
    }
}
