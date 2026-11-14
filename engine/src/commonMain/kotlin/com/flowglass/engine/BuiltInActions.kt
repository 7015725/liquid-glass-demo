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
    ) = ParamSpec(key, label, type, required = required, defaultValue = when (type) {
        ParamType.PERCENTAGE -> JsonPrimitive(default.removeSuffix("%").toInt())
        ParamType.DURATION -> JsonPrimitive(1000)
        else -> JsonPrimitive(default)
    }, hint = hint)

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
                hint = "格式：非空 Shell 命令，执行前需要 Root 授权。请只执行你理解其作用的命令。")),
            minTier = Tier.ROOT, outputs = listOf("shell.stdout")),
        ComponentMeta("action.http_request", "HTTP 请求", "网络", ComponentKind.ACTION,
            listOf(parameter("request", "请求", ParamType.MULTILINE_TEXT, "GET https://example.com", false,
                hint = "格式：GET <URL> 或 POST <URL> <内容>；GET 不带正文，POST 内容可换行。")),
            outputs = listOf("http.body")),
    )

    fun registerInto(registry: ComponentRegistry) = metadata.forEach(registry::register)
}
