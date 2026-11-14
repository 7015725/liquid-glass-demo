package com.flowglass.engine

import kotlinx.serialization.json.JsonPrimitive

object BuiltInActions {
    private fun parameter(
        key: String,
        label: String,
        type: ParamType = ParamType.TEXT,
        default: String = "",
        required: Boolean = true,
        hint: String = "",
        expansion: ParamExpansion = ParamExpansion.PLAIN,
        durationMs: Long = 1000,
    ) = ParamSpec(key, label, type, required = required, defaultValue = when (type) {
        ParamType.PERCENTAGE -> JsonPrimitive(default.removeSuffix("%").toInt())
        ParamType.DURATION -> JsonPrimitive(durationMs)
        else -> JsonPrimitive(default)
    }, hint = hint, expansion = expansion)

    val metadata: List<ComponentMeta> = listOf(
        ComponentMeta("action.wifi_switch", "开关 WiFi", "网络", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"), ParameterCodec.switchOptions)), minTier = Tier.NORMAL),
        ComponentMeta("action.bluetooth_switch", "开关蓝牙", "网络", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"), ParameterCodec.switchOptions)), permissions = listOf("bluetooth_connect")),
        ComponentMeta("action.dnd_switch", "开关勿扰", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"), ParameterCodec.switchOptions)), permissions = listOf("dnd")),
        ComponentMeta("action.set_brightness", "调节亮度", "设备", ComponentKind.ACTION,
            listOf(parameter("value", "亮度", ParamType.PERCENTAGE, "50%", false)), permissions = listOf("write_settings")),
        ComponentMeta("action.set_volume", "调节音量", "设备", ComponentKind.ACTION,
            listOf(parameter("value", "音量", ParamType.PERCENTAGE, "50%", false))),
        ComponentMeta("action.launch_app", "启动应用", "应用", ComponentKind.ACTION,
            listOf(parameter("package", "应用", ParamType.APPLICATION, "应用包名"))),
        ComponentMeta("action.post_notification", "发送通知", "通知", ComponentKind.ACTION,
            listOf(parameter("content", "内容", default = "任务已完成", required = false),
                parameter("title", "通知标题", default = "", required = false)), permissions = listOf("notify")),
        ComponentMeta("action.copy_clipboard", "复制到剪贴板", "文本", ComponentKind.ACTION,
            listOf(parameter("text", "文本", ParamType.MULTILINE_TEXT, "待复制文本", false,
                hint = "格式：纯文本，支持换行；按原文复制，不解析命令。"))),
        ComponentMeta("action.delay", "延时等待", "流程控制", ComponentKind.ACTION,
            listOf(parameter("duration", "时长", ParamType.DURATION, "1 秒", false,
                hint = "格式：非负数字 + 单位（毫秒 / 秒 / 分钟），超过 10 分钟按 10 分钟等待。"))),
        ComponentMeta("action.root_shell", "执行 Shell(Root)", "脚本", ComponentKind.ACTION,
            listOf(parameter("command", "命令", ParamType.MULTILINE_TEXT, "echo 流光", false,
                hint = "格式：非空 Shell 命令，执行前需要 Root 授权。变量值会先写入 FG_Vn 再以引用形式使用；请只执行你理解其作用的命令。",
                expansion = ParamExpansion.SHELL_QUOTED)),
            minTier = Tier.ROOT, outputs = listOf("shell.stdout")),
        ComponentMeta("action.http_request", "HTTP 请求", "网络", ComponentKind.ACTION,
            listOf(parameter("request", "请求", ParamType.MULTILINE_TEXT, "GET https://example.com", false,
                hint = "格式：GET <URL> 或 POST <URL> <内容>；GET 不带正文，POST 内容可换行。",
                expansion = ParamExpansion.HTTP_REQUEST)),
            outputs = listOf("http.body")),
        ComponentMeta("action.vibrate", "振动", "设备", ComponentKind.ACTION,
            listOf(parameter("duration", "时长", ParamType.DURATION, "500 毫秒", false,
                hint = "格式：非负数字 + 毫秒 / 秒 / 分钟。", durationMs = 500))),
        ComponentMeta("action.flashlight", "手电筒", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("toggle"),
                ParameterCodec.switchOptions))),
        ComponentMeta("action.ringer_mode", "响铃模式", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.ENUM, true, JsonPrimitive("ring"), listOf(
                ParamOption("ring", "响铃"), ParamOption("vibrate", "振动"), ParamOption("silent", "静音")),
                hint = "静音需要勿扰权限。"))),
        ComponentMeta("action.media_control", "媒体控制", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("action", "操作", ParamType.ENUM, true, JsonPrimitive("play"), listOf(
                ParamOption("play", "播放"), ParamOption("pause", "暂停"),
                ParamOption("previous", "上一首"), ParamOption("next", "下一首"))))),
        ComponentMeta("action.open_url", "打开网页", "网络", ComponentKind.ACTION,
            listOf(parameter("url", "网址", ParamType.TEXT, "https://example.com",
                hint = "格式：以 http:// 或 https:// 开头的网址，支持 %{变量}。"))),
        ComponentMeta("action.speak", "文字朗读", "设备", ComponentKind.ACTION,
            listOf(parameter("text", "内容", ParamType.MULTILINE_TEXT, "你好，流光", false,
                hint = "使用系统 TTS 朗读；朗读完成或 30 秒超时后进入下一步。"))),
        ComponentMeta("action.toast", "屏幕提示", "通知", ComponentKind.ACTION,
            listOf(parameter("text", "内容", ParamType.TEXT, "任务已完成", false))),
        ComponentMeta("action.screen_timeout", "屏幕超时", "设备", ComponentKind.ACTION,
            listOf(parameter("duration", "时长", ParamType.DURATION, "30 秒", false,
                hint = "范围 5 秒–30 分钟；需要修改系统设置权限。", durationMs = 30_000)),
            permissions = listOf("write_settings")),
        ComponentMeta("action.auto_rotate", "自动旋转", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"),
                ParameterCodec.switchOptions)), permissions = listOf("write_settings")),
        ComponentMeta("action.global_action", "系统导航", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("action", "操作", ParamType.ENUM, true, JsonPrimitive("home"), listOf(
                ParamOption("back", "返回"), ParamOption("home", "主页"),
                ParamOption("recents", "最近任务"), ParamOption("lock", "锁屏"),
                ParamOption("screenshot", "截屏")))), permissions = listOf("a11y")),
        ComponentMeta("action.airplane_mode", "飞行模式", "网络", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"),
                ParameterCodec.switchOptions)), minTier = Tier.ROOT),
        ComponentMeta("action.mobile_data", "移动数据", "网络", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"),
                ParameterCodec.switchOptions)), minTier = Tier.ROOT),
    )

    fun registerInto(registry: ComponentRegistry) = metadata.forEach(registry::register)
}
