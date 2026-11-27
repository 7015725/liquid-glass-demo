package com.flowglass.engine

import kotlinx.serialization.json.JsonPrimitive

object BuiltInComponents {
    private fun out(
        key: String,
        label: String,
        type: VariableType,
        defaultName: String,
        description: String = "",
    ) = OutputSpec(key, label, type, defaultName, description)

    private fun param(
        key: String,
        label: String,
        default: String = "",
        type: ParamType = ParamType.TEXT,
        required: Boolean = false,
        options: List<ParamOption> = emptyList(),
        hint: String = "",
        source: ParamSource = ParamSource.NONE,
        editor: ParamEditor = ParamEditor.AUTO,
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
        editor = editor,
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
            ), supports = normalLayer),
        ComponentMeta("trigger.screen_on", "亮屏", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.screen_off", "熄屏", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.user_present", "解锁", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.power_connected", "插入充电器", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.power_disconnected", "拔出充电器", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.battery_level", "电量", "设备", ComponentKind.TRIGGER,
            listOf(
                param("direction", "方向", "below", ParamType.ENUM, required = true,
                    options = listOf(
                        ParamOption("below", "低于"),
                        ParamOption("above", "高于"),
                        ParamOption("any", "任意变化"),
                    ),
                    hint = "选「任意变化」时阈值不生效，30 秒内最多触发一次。"),
                param("threshold", "阈值", "20%", ParamType.PERCENTAGE, required = true),
            ), supports = normalLayer, outputs = listOf(
                out("trigger.battery.level", "电量", VariableType.INT, "电量", "触发时的电池电量（0–100）"),
            )),
        ComponentMeta("trigger.boot_completed", "开机完成", "设备", ComponentKind.TRIGGER, supports = normalLayer),
        ComponentMeta("trigger.battery_full", "电池充满", "设备", ComponentKind.TRIGGER, supports = normalLayer,
            description = "电池状态从充电中变为充满时触发；已在充满状态启动自动化时不会补触发。",
            outputs = listOf(
                out("trigger.battery.level", "电量", VariableType.INT, "电量", "触发时的电池电量（0–100）"),
            )),
        ComponentMeta("trigger.battery_overheat", "电池过热", "设备", ComponentKind.TRIGGER,
            listOf(ParamSpec(
                "temperature", "温度阈值", ParamType.INTEGER, required = true,
                defaultValue = JsonPrimitive(45), minValue = 30, maxValue = 80, suffix = "°C",
                hint = "温度从低于阈值升到阈值以上时触发；部分设备不报告温度。",
            )), supports = normalLayer,
            description = "电池温度越过阈值时触发；同一段过热只触发一次。",
            outputs = listOf(
                out("trigger.battery.temperature", "电池温度", VariableType.INT, "电池温度", "触发时的电池温度（°C）"),
            )),
        ComponentMeta("trigger.storage_media", "存储卡挂载/卸载", "设备", ComponentKind.TRIGGER,
            listOf(param("event", "事件", "any", ParamType.ENUM, options = listOf(
                ParamOption("any", "任意"),
                ParamOption("mounted", "已挂载"),
                ParamOption("unmounted", "已卸载"),
                ParamOption("removed", "已移除"),
            ))), supports = normalLayer,
            description = "可移动存储挂载、卸载或移除时触发；内置存储不会产生这些广播。",
            outputs = listOf(
                out("trigger.storage.path", "存储路径", VariableType.TEXT, "存储路径", "本次事件的挂载点"),
                out("trigger.storage.event", "存储事件", VariableType.TEXT, "存储事件", "mounted、unmounted 或 removed"),
            )),
        ComponentMeta("trigger.storage_low", "存储空间不足", "设备", ComponentKind.TRIGGER,
            listOf(param("threshold", "剩余阈值", "10%", ParamType.PERCENTAGE, required = true)),
            supports = normalLayer,
            description = "系统报告低存储或周期检查低于阈值时触发；低于阈值期间最多每 30 分钟提醒一次。",
            outputs = listOf(
                out("trigger.storage.freePercent", "剩余空间", VariableType.INT, "剩余空间", "触发时内置存储的剩余百分比"),
            )),
        ComponentMeta("trigger.system_time_changed", "系统时间/时区变更", "系统", ComponentKind.TRIGGER,
            listOf(param("event", "变更类型", "any", ParamType.ENUM, options = listOf(
                ParamOption("any", "任意"),
                ParamOption("date", "日期"),
                ParamOption("time", "时间"),
                ParamOption("timezone", "时区"),
            ))), supports = normalLayer,
            description = "系统日期、时间或时区被修改时触发；日期变更每天零点也会发生。",
            outputs = listOf(
                out("trigger.system_time.kind", "变更类型", VariableType.TEXT, "变更类型", "date、time 或 timezone"),
            )),
        ComponentMeta("trigger.next_alarm_changed", "下一个闹钟变化", "系统", ComponentKind.TRIGGER,
            listOf(param("package", "闹钟应用", "", ParamType.TEXT, required = false,
                hint = "留空匹配任意应用；填写包名后精确匹配，例如 com.android.deskclock。")),
            supports = normalLayer,
            description = "系统下一个闹钟被设置、修改或取消时触发；取消时输出为空。",
            outputs = listOf(
                out("trigger.alarm.time", "响铃时间", VariableType.TEXT, "响铃时间", "下一个闹钟的本地时间 HH:mm；无闹钟时为空"),
                out("trigger.alarm.package", "闹钟应用", VariableType.TEXT, "闹钟应用", "设置闹钟的应用包名"),
            )),
        ComponentMeta("trigger.shutdown", "设备关闭", "系统", ComponentKind.TRIGGER, supports = normalLayer,
            description = "系统开始关机时触发；触发后可执行时间很短，不适合长任务。"),
        ComponentMeta("trigger.locale_changed", "区域设置变更", "系统", ComponentKind.TRIGGER,
            supports = normalLayer,
            description = "系统语言或区域设置变化时触发。",
            outputs = listOf(
                out("trigger.locale.tag", "区域标签", VariableType.TEXT, "区域标签", "变化后的 BCP 47 标签，例如 zh-CN"),
            )),
        ComponentMeta("trigger.wallpaper_changed", "壁纸已更改", "系统", ComponentKind.TRIGGER,
            supports = normalLayer,
            description = "系统壁纸变化时触发。"),
        ComponentMeta("trigger.setting_changed", "系统设置变更", "系统", ComponentKind.TRIGGER,
            listOf(
                param("namespace", "设置命名空间", "system", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("system", "system"),
                    ParamOption("secure", "secure"),
                    ParamOption("global", "global"),
                )),
                param("key", "设置项", "screen_brightness", ParamType.TEXT, required = true,
                    hint = "填写设置键名，例如 screen_brightness；可在 adb shell settings list 中查看。"),
            ), supports = normalLayer,
            description = "指定系统设置项变化时触发；按命名空间和键名精确匹配。",
            outputs = listOf(
                out("trigger.setting.namespace", "命名空间", VariableType.TEXT, "命名空间", "system、secure 或 global"),
                out("trigger.setting.key", "设置项", VariableType.TEXT, "设置项", "变化的设置键名"),
                out("trigger.setting.value", "设置值", VariableType.TEXT, "设置值", "变化后的值；读取失败时为空"),
            )),
        ComponentMeta("trigger.accessibility_changed", "无障碍服务变更", "系统", ComponentKind.TRIGGER,
            supports = normalLayer,
            description = "系统已启用的无障碍服务列表变化时触发。",
            outputs = listOf(
                out("trigger.accessibility.count", "服务数量", VariableType.INT, "服务数量", "变化后已启用的服务数量"),
                out("trigger.accessibility.services", "服务列表", VariableType.TEXT_LIST, "服务列表", "已启用的无障碍服务组件名"),
            )),
        ComponentMeta("trigger.package_change", "应用安装/卸载/更新", "应用", ComponentKind.TRIGGER,
            listOf(param("event", "事件", "any", ParamType.ENUM, options = listOf(
                ParamOption("any", "任意"),
                ParamOption("added", "新安装"),
                ParamOption("removed", "已卸载"),
                ParamOption("replaced", "已更新"),
            ))), supports = normalLayer,
            description = "应用安装、卸载或更新时触发；更新只触发一次，不会同时报卸载和安装。",
            outputs = listOf(
                out("trigger.package.name", "应用包名", VariableType.TEXT, "应用包名", "发生变化的应用包名"),
                out("trigger.package.label", "应用名称", VariableType.TEXT, "应用名称", "应用显示名称；读取失败时为空"),
                out("trigger.package.event", "应用事件", VariableType.TEXT, "应用事件", "added、removed 或 replaced"),
            )),
        ComponentMeta("trigger.tick", "周期滴答", "定时", ComponentKind.TRIGGER,
            listOf(ParamSpec(
                "interval", "间隔", ParamType.INTEGER, required = true,
                defaultValue = JsonPrimitive(30), minValue = 5, maxValue = 86_400, suffix = "秒",
                hint = "应用进程存活时按间隔触发；强行停止应用后停止，重新打开后恢复。",
            )), supports = normalLayer,
            description = "固定间隔触发，用于轮询或心跳；不是精确闹钟，息屏时可能被系统延后。",
            outputs = listOf(
                out("trigger.tick.interval", "间隔", VariableType.INT, "间隔", "本触发器配置的间隔（秒）"),
            )),

        // Kept for rules saved by earlier builds. Hidden from the add panel; the runtime maps
        // them onto the new battery / power sources so existing rules keep working.
        ComponentMeta("trigger.battery_low", "电量低于", "设备", ComponentKind.TRIGGER,
            listOf(param("threshold", "电量", "20%", ParamType.PERCENTAGE)), supports = normalLayer,
            outputs = listOf(
                out("trigger.battery.level", "电量", VariableType.INT, "电量", "触发时的电池电量（0–100）"),
            )),
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
            ), permissions = listOf("location"), supports = normalSupport("location"),
            outputs = listOf(
                out("trigger.wifi.ssid", "WiFi 名称", VariableType.TEXT, "WiFi名称", "本次事件的网络名称"),
                out("trigger.wifi.state", "WiFi 状态", VariableType.TEXT, "WiFi状态", "connected 或 disconnected"),
            )),
        ComponentMeta("trigger.bluetooth", "蓝牙连接/断开", "网络", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "connected", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("connected", "已连接"), ParamOption("disconnected", "已断开"))),
                param("name", "设备名称", "", ParamType.TEXT, required = false,
                    hint = "留空匹配任意设备；填写后按名称包含匹配。",
                    source = ParamSource.PAIRED_BLUETOOTH),
            ), permissions = listOf("bluetooth_connect"), supports = normalSupport("bluetooth_connect"),
            outputs = listOf(
                out("trigger.bluetooth.name", "蓝牙名称", VariableType.TEXT, "蓝牙名称", "本次事件的设备名称"),
                out("trigger.bluetooth.state", "蓝牙状态", VariableType.TEXT, "蓝牙状态", "connected 或 disconnected"),
            )),
        ComponentMeta("trigger.notification", "收到通知", "通知", ComponentKind.TRIGGER,
            listOf(
                param("app", "应用", "", ParamType.APPLICATION, required = false,
                    hint = "留空匹配任意应用。"),
                param("title", "标题关键字", "", ParamType.TEXT, required = false),
                param("text", "内容关键字", "", ParamType.TEXT, required = false),
            ), permissions = listOf("notification_listener"), supports = normalSupport("notification_listener"),
            outputs = listOf(
                out("trigger.notification.app", "通知应用", VariableType.TEXT, "通知应用", "发出通知的应用包名；分身会带 @用户"),
                out("trigger.notification.user", "通知用户", VariableType.INT, "通知用户", "通知所属的 Android 用户 ID"),
                out("trigger.notification.title", "通知标题", VariableType.TEXT, "通知标题", "收到的通知标题"),
                out("trigger.notification.text", "通知内容", VariableType.TEXT, "通知内容", "收到的通知正文"),
            )),
        ComponentMeta("trigger.app_foreground", "应用前台/离开", "应用", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "foreground", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("foreground", "切到前台"), ParamOption("background", "离开前台"))),
                param("package", "应用", "应用包名", ParamType.APPLICATION, required = true,
                    hint = "LSPosed 系统钩子或 Root 可用时支持分身；普通方式只识别主应用。",
                    applicationScope = ApplicationScope.ALL_PROFILES),
            ), permissions = listOf("foreground_app"), supports = listOf(
                LayerSupport(ExecutionLayer.XPOSED),
                LayerSupport(ExecutionLayer.ROOT),
                LayerSupport(ExecutionLayer.NORMAL, permissions = listOf("foreground_app")),
            ),
            applicationCapability = ApplicationCapability.FOREGROUND_EVENTS,
            outputs = listOf(
                out("trigger.app.package", "应用包名", VariableType.TEXT, "应用包名", "事件所属应用包名；分身会带 @用户"),
                out("trigger.app.user", "应用用户", VariableType.INT, "应用用户", "事件所属的 Android 用户 ID"),
            )),
        ComponentMeta("trigger.app_process", "应用进程启动/结束", "应用", ComponentKind.TRIGGER,
            listOf(
                param("event", "事件", "started", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("started", "进程启动"), ParamOption("ended", "进程结束"))),
                param("package", "应用", "应用包名", ParamType.APPLICATION, required = true,
                    hint = "需要 LSPosed 系统钩子；支持 package@userId 分身。",
                    applicationScope = ApplicationScope.ALL_PROFILES),
            ), supports = listOf(LayerSupport(ExecutionLayer.XPOSED)),
            minTier = Tier.XPOSED,
            description = "观察应用进程的启动和结束，不是只观察前台 Activity。",
            outputs = listOf(
                out("trigger.app.package", "应用包名", VariableType.TEXT, "应用包名", "事件所属应用包名；分身会带 @用户"),
                out("trigger.app.user", "应用用户", VariableType.INT, "应用用户", "事件所属的 Android 用户 ID"),
            ),
        ),
        ComponentMeta("trigger.app_crash", "应用崩溃 / ANR", "应用", ComponentKind.TRIGGER,
            listOf(
                param("event", "事件", "crash", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("crash", "崩溃"), ParamOption("anr", "无响应（ANR）"))),
                param("package", "应用", "应用包名", ParamType.APPLICATION, required = true,
                    hint = "需要 LSPosed 系统钩子；只发送事件类型，不读取堆栈或异常内容。",
                    applicationScope = ApplicationScope.ALL_PROFILES),
            ), supports = listOf(LayerSupport(ExecutionLayer.XPOSED)),
            minTier = Tier.XPOSED,
            description = "应用崩溃或 ANR 时触发；不发送崩溃堆栈。",
            outputs = listOf(
                out("trigger.app.package", "应用包名", VariableType.TEXT, "应用包名", "事件所属应用包名；分身会带 @用户"),
                out("trigger.app.user", "应用用户", VariableType.INT, "应用用户", "事件所属的 Android 用户 ID"),
                out("trigger.app.event", "应用事件", VariableType.TEXT, "应用事件", "crash 或 anr"),
            ),
        ),
        ComponentMeta("trigger.keyguard", "锁屏 / 解锁", "设备", ComponentKind.TRIGGER,
            listOf(param("state", "状态", "locked", ParamType.ENUM, required = true, options = listOf(
                ParamOption("locked", "已锁屏"), ParamOption("unlocked", "已解锁")))),
            supports = listOf(LayerSupport(ExecutionLayer.XPOSED)),
            minTier = Tier.XPOSED,
            description = "只观察锁屏状态变化；需要 LSPosed 系统钩子。",
            outputs = listOf(
                out("trigger.keyguard.state", "锁屏状态", VariableType.TEXT, "锁屏状态", "locked 或 unlocked"),
            )
        ),
        ComponentMeta("trigger.media", "媒体播放状态", "媒体", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "playing", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("playing", "正在播放"),
                    ParamOption("paused", "已暂停"),
                    ParamOption("stopped", "已停止"),
                )),
                param("package", "应用", "应用包名", ParamType.APPLICATION, required = true,
                    hint = "需要 LSPosed 系统钩子；支持 package@userId 分身。",
                    applicationScope = ApplicationScope.ALL_PROFILES),
            ), supports = listOf(LayerSupport(ExecutionLayer.XPOSED)),
            minTier = Tier.XPOSED,
            description = "观察媒体会话的播放 / 暂停 / 停止状态；只读，不控制播放。",
            outputs = listOf(
                out("trigger.app.package", "应用包名", VariableType.TEXT, "应用包名", "事件所属应用包名；分身会带 @用户"),
                out("trigger.app.user", "应用用户", VariableType.INT, "应用用户", "事件所属的 Android 用户 ID"),
                out("trigger.app.event", "应用事件", VariableType.TEXT, "应用事件", "playing、paused 或 stopped"),
            ),
        ),
        ComponentMeta("trigger.foreground_service", "前台服务", "应用", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "started", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("started", "进入前台"),
                    ParamOption("stopped", "退出前台"),
                )),
                param("package", "应用", "应用包名", ParamType.APPLICATION, required = true,
                    hint = "需要 LSPosed 系统钩子；只观察前台服务状态。",
                    applicationScope = ApplicationScope.ALL_PROFILES),
            ), supports = listOf(LayerSupport(ExecutionLayer.XPOSED)),
            minTier = Tier.XPOSED,
            description = "观察应用前台服务的进入 / 退出；只读，不读取通知内容。",
            outputs = listOf(
                out("trigger.app.package", "应用包名", VariableType.TEXT, "应用包名", "事件所属应用包名；分身会带 @用户"),
                out("trigger.app.user", "应用用户", VariableType.INT, "应用用户", "事件所属的 Android 用户 ID"),
            ),
        ),
        ComponentMeta("trigger.app_method_call", "应用方法调用", "应用", ComponentKind.TRIGGER,
            listOf(
                param("package", "目标应用", "", ParamType.APPLICATION, required = true,
                    hint = "在 LSPosed 作用域中勾选目标应用；支持 package@userId 分身。",
                    applicationScope = ApplicationScope.ALL_PROFILES),
                param("className", "类名", "", ParamType.TEXT, required = true,
                    hint = "例如 com.example.app.OrderManager；只查目标类自身声明的方法。"),
                param("methodName", "方法名", "", ParamType.TEXT, required = true,
                    hint = "例如 onPaySuccess。"),
                ParamSpec("paramTypes", "参数类型（可选）", ParamType.TEXT, required = false,
                    defaultValue = JsonPrimitive(""),
                    hint = "用逗号分隔，例如 java.lang.String,int；留空 = 所有同名方法都监听。"),
                ParamSpec("extractions", "提取数据", ParamType.MULTILINE_TEXT, required = false,
                    defaultValue = JsonPrimitive(""),
                    hint = "可选。每一项把参数或返回值复制到 {变量名}；只读字段，不执行代码。",
                    editor = ParamEditor.APP_METHOD_EXTRACTIONS),
            ), supports = listOf(LayerSupport(ExecutionLayer.XPOSED)),
            minTier = Tier.XPOSED,
            description = "当指定应用里的某个方法被调用时触发（需 LSPosed）",
            outputs = listOf(
                out("trigger.method.app", "目标应用", VariableType.TEXT, "调用应用", "目标应用包名；分身会带 @用户"),
                out("trigger.method.user", "目标用户", VariableType.INT, "调用用户", "目标应用的 Android 用户 ID"),
                out("trigger.method.process", "触发进程", VariableType.TEXT, "调用进程", "实际触发进程名"),
                out("trigger.method.method", "方法", VariableType.TEXT, "调用方法", "类名#方法名"),
                out("trigger.method.signature", "签名", VariableType.TEXT, "调用签名", "实际匹配的参数签名"),
                out("trigger.method.hookId", "调用标识", VariableType.TEXT, "调用标识", "本规则的钩子标识"),
            ),
        ),
        ComponentMeta("trigger.headset", "耳机插入/拔出", "设备", ComponentKind.TRIGGER,
            listOf(
                param("state", "状态", "plugged", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("plugged", "已插入"), ParamOption("unplugged", "已拔出"))),
                param("kind", "类型", "any", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("any", "任意耳机"), ParamOption("wired", "有线耳机"),
                    ParamOption("bluetooth", "蓝牙音频设备"))),
            ), supports = normalLayer,
            outputs = listOf(
                out("trigger.headset.name", "耳机名称", VariableType.TEXT, "耳机名称", "耳机或蓝牙音频设备名称"),
                out("trigger.headset.kind", "耳机类型", VariableType.TEXT, "耳机类型", "wired 或 bluetooth"),
                out("trigger.headset.state", "耳机状态", VariableType.TEXT, "耳机状态", "plugged 或 unplugged"),
            )),
        ComponentMeta("trigger.location_enter", "进入地点", "位置", ComponentKind.TRIGGER,
            listOf(
                ParamSpec("location", "地点", ParamType.TEXT, defaultValue = JsonPrimitive(""),
                    hint = "纬度,经度，例如 31.2304,121.4737",
                    editor = ParamEditor.LOCATION, source = ParamSource.CURRENT_LOCATION),
                ParamSpec("radius", "半径", ParamType.INTEGER, defaultValue = JsonPrimitive(500),
                    hint = "100–5000 米", minValue = 100, maxValue = 5000),
            ), permissions = listOf("location"), supports = normalSupport("location"),
            outputs = listOf(
                out("trigger.location.lat", "纬度", VariableType.TEXT, "纬度", "位置事件的纬度"),
                out("trigger.location.lng", "经度", VariableType.TEXT, "经度", "位置事件的经度"),
            )),
        ComponentMeta("trigger.location_exit", "离开地点", "位置", ComponentKind.TRIGGER,
            listOf(
                ParamSpec("location", "地点", ParamType.TEXT, defaultValue = JsonPrimitive(""),
                    hint = "纬度,经度，例如 31.2304,121.4737",
                    editor = ParamEditor.LOCATION, source = ParamSource.CURRENT_LOCATION),
                ParamSpec("radius", "半径", ParamType.INTEGER, defaultValue = JsonPrimitive(500),
                    hint = "100–5000 米", minValue = 100, maxValue = 5000),
            ), permissions = listOf("location"), supports = normalSupport("location"),
            outputs = listOf(
                out("trigger.location.lat", "纬度", VariableType.TEXT, "纬度", "位置事件的纬度"),
                out("trigger.location.lng", "经度", VariableType.TEXT, "经度", "位置事件的经度"),
            )),
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
        ComponentMeta("condition.app_process_state", "应用进程状态", "应用", ComponentKind.CONDITION,
            listOf(
                ParamSpec("package", "应用", ParamType.APPLICATION, defaultValue = JsonPrimitive("应用包名"),
                    hint = "需要 LSPosed 进程事件；数据过期时按不满足处理。",
                    applicationScope = ApplicationScope.ALL_PROFILES,
                    source = ParamSource.LAUNCHER_APPS),
                param("state", "状态", "running", ParamType.ENUM, required = true, options = listOf(
                    ParamOption("running", "运行中"), ParamOption("not_running", "未运行"))),
            ), supports = normalLayer,
            description = "读取最近一次 LSPosed 进程事件缓存；缓存过期返回不满足。",
        ),
        ComponentMeta("condition.network_type", "网络类型", "网络", ComponentKind.CONDITION,
            listOf(param("network", "网络类型", "wifi", ParamType.ENUM, options = listOf(
                ParamOption("wifi", "WiFi"), ParamOption("mobile", "移动网络"),
                ParamOption("none", "无网络")))), supports = normalLayer),
        ComponentMeta("condition.dnd_state", "勿扰状态", "设备", ComponentKind.CONDITION,
            listOf(param("state", "勿扰状态", "off", ParamType.ENUM, options = listOf(
                ParamOption("on", "已开启"), ParamOption("off", "已关闭")))),
            permissions = listOf("dnd"), supports = normalSupport("dnd")),
        ComponentMeta("condition.variable", "变量判断", "流程控制", ComponentKind.CONDITION,
            listOf(
                param("left", "变量", "", ParamType.TEXT,
                    hint = "从内置、局部或全局变量中选择，也可手动填写 {名字}；文件判断可直接填写路径。"),
                param("op", "判断方式", "eq", ParamType.ENUM, options = listOf(
                    ParamOption("gt", "大于"), ParamOption("ge", "大于等于"),
                    ParamOption("lt", "小于"), ParamOption("le", "小于等于"),
                    ParamOption("eq", "等于"), ParamOption("ne", "不等于"),
                    ParamOption("contains", "包含"), ParamOption("not_contains", "不包含"),
                    ParamOption("unset", "未设置"), ParamOption("set", "已设置"),
                    ParamOption("empty", "为空"), ParamOption("not_empty", "不为空"),
                    ParamOption("file_exists", "文件存在"), ParamOption("file_not_exists", "文件不存在"),
                )),
                param("right", "比较值", "", ParamType.TEXT,
                    hint = "只有需要第二个值的判断才显示；文本不需要加引号。"),
            ),
            description = "按整数、真假、文本、状态或文件判断变量；文件判断优先使用 Root 执行层。",
            supports = listOf(
                LayerSupport(ExecutionLayer.ROOT),
                LayerSupport(ExecutionLayer.NORMAL),
            ),
        ),
    )

    val all: List<ComponentMeta> = triggers + conditions + BuiltInActions.metadata + BuiltInBlocks.metadata

    fun registry(): ComponentRegistry = ComponentRegistry().also { registry ->
        all.forEach(registry::register)
    }
}
