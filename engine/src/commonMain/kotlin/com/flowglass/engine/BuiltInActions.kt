package com.flowglass.engine

import kotlinx.serialization.json.JsonPrimitive

object BuiltInActions {
    private fun out(
        key: String,
        label: String,
        type: VariableType,
        defaultName: String,
        description: String = "",
    ) = OutputSpec(key, label, type, defaultName, description)

    private val normalLayer = listOf(LayerSupport(ExecutionLayer.NORMAL))
    private val rootLayer = listOf(LayerSupport(ExecutionLayer.ROOT))
    private val rootAndNormal = listOf(
        LayerSupport(ExecutionLayer.ROOT),
        LayerSupport(ExecutionLayer.NORMAL),
    )
    private fun normalSupport(vararg permissions: String) =
        listOf(LayerSupport(ExecutionLayer.NORMAL, permissions.toList()))
    private fun rootAndNormalSupport(vararg permissions: String) = listOf(
        LayerSupport(ExecutionLayer.ROOT),
        LayerSupport(ExecutionLayer.NORMAL, permissions.toList()),
    )

    private fun parameter(
        key: String,
        label: String,
        type: ParamType = ParamType.TEXT,
        default: String = "",
        required: Boolean = true,
        hint: String = "",
        expansion: ParamExpansion = ParamExpansion.PLAIN,
        durationMs: Long = 1000,
        editor: ParamEditor = ParamEditor.AUTO,
    ) = ParamSpec(key, label, type, required = required, defaultValue = when (type) {
        ParamType.PERCENTAGE -> JsonPrimitive(default.removeSuffix("%").toInt())
        ParamType.DURATION -> JsonPrimitive(durationMs)
        else -> JsonPrimitive(default)
    }, hint = hint, expansion = expansion, editor = editor)

    val metadata: List<ComponentMeta> = listOf(
        ComponentMeta("action.wifi_switch", "开关 WiFi", "网络", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"), ParameterCodec.switchOptions)),
            minTier = Tier.NORMAL, supports = rootAndNormal),
        ComponentMeta("action.bluetooth_switch", "开关蓝牙", "网络", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"), ParameterCodec.switchOptions)),
            permissions = listOf("bluetooth_connect"),
            supports = listOf(LayerSupport(ExecutionLayer.ROOT), LayerSupport(ExecutionLayer.NORMAL, listOf("bluetooth_connect")))),
        ComponentMeta("action.dnd_switch", "开关勿扰", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"), ParameterCodec.switchOptions)),
            permissions = listOf("dnd"), supports = rootAndNormalSupport("dnd")),
        ComponentMeta("action.set_brightness", "调节亮度", "设备", ComponentKind.ACTION,
            listOf(parameter("value", "亮度", ParamType.PERCENTAGE, "50%", false)),
            permissions = listOf("write_settings"), supports = rootAndNormalSupport("write_settings")),
        ComponentMeta("action.set_volume", "调节音量", "设备", ComponentKind.ACTION,
            listOf(parameter("value", "音量", ParamType.PERCENTAGE, "50%", false)), supports = normalLayer),
        ComponentMeta("action.launch_app", "启动应用", "应用", ComponentKind.ACTION,
            listOf(parameter("package", "应用", ParamType.APPLICATION, "应用包名")), supports = rootAndNormal),
        ComponentMeta("action.force_stop", "强制停止应用", "应用", ComponentKind.ACTION,
            listOf(ParamSpec(
                key = "package",
                label = "应用",
                type = ParamType.APPLICATION,
                required = true,
                defaultValue = JsonPrimitive("应用包名"),
                hint = "只允许普通应用，禁止 android、启动器、SystemUI 和本应用；支持 package@userId。",
                applicationScope = ApplicationScope.ALL_PROFILES,
                source = ParamSource.LAUNCHER_APPS,
            )),
            minTier = Tier.ROOT, supports = rootLayer,
            description = "通过 Root 执行 am force-stop；包名和 userId 会先做白名单校验。",
        ),
        ComponentMeta("action.post_notification", "发送通知", "通知", ComponentKind.ACTION,
            listOf(parameter("content", "内容", default = "任务已完成", required = false),
                parameter("title", "通知标题", default = "", required = false),
                ParamSpec("mode", "通知方式", ParamType.ENUM, false, JsonPrimitive("system"), listOf(
                    ParamOption("system", "系统通知"), ParamOption("toast", "Toast"),
                    ParamOption("both", "两者都发"))),
                ParamSpec("toastDuration", "Toast 时长", ParamType.ENUM, false, JsonPrimitive("short"), listOf(
                    ParamOption("short", "短"), ParamOption("long", "长")))),
            permissions = listOf("notify"), supports = normalSupport("notify")),
        ComponentMeta("action.copy_clipboard", "复制到剪贴板", "文本", ComponentKind.ACTION,
            listOf(parameter("text", "文本", ParamType.MULTILINE_TEXT, "待复制文本", false,
                hint = "格式：纯文本，支持换行；按原文复制，不解析命令。")), supports = normalLayer),
        ComponentMeta("action.delay", "延时等待", "流程控制", ComponentKind.ACTION,
            listOf(parameter("duration", "时长", ParamType.DURATION, "1 秒", false,
                hint = "格式：非负数字 + 单位（毫秒 / 秒 / 分钟），超过 10 分钟按 10 分钟等待。")),
            supports = normalLayer),
        ComponentMeta("action.root_shell", "执行 Shell(Root)", "脚本", ComponentKind.ACTION,
            listOf(parameter("command", "命令", ParamType.MULTILINE_TEXT, "echo 流光", false,
                hint = "格式：非空 Shell 命令，执行前需要 Root 授权。变量值会先写入 FG_Vn 再以引用形式使用；请只执行你理解其作用的命令。",
                expansion = ParamExpansion.SHELL_QUOTED, editor = ParamEditor.CODE)),
            minTier = Tier.ROOT, supports = rootLayer, outputs = listOf(
                out("shell.stdout", "Shell 标准输出", VariableType.TEXT, "Shell标准输出", "命令写入标准输出（stdout）的内容，与标准错误分开采集"),
                out("shell.stderr", "Shell 标准错误", VariableType.TEXT, "Shell标准错误", "命令写入标准错误（stderr）的内容，与标准输出分开采集"),
                out("shell.code", "Shell 退出码", VariableType.INT, "Shell退出码", "命令的退出码；超时或输出超限时为 -1"),
            )),
        ComponentMeta("action.script", "执行 JavaScript", "脚本", ComponentKind.ACTION,
            listOf(
                ParamSpec("script", "脚本", ParamType.MULTILINE_TEXT, true,
                    JsonPrimitive("log(\"hello\");\n\"done\";"),
                    hint = "JavaScript（Rhino）。示例：\n" +
                        "log(\"开始\");\n" +
                        "var count = get(\"次数\") || \"0\";\n" +
                        "set(\"次数\", Number(count) + 1);\n" +
                        "set(\"最后结果\", \"ok\");\n" +
                        "\"完成\";\n" +
                        "可用 API：get(name)、set(name, value)、log(text)、toast(text)、notify(title, text)、http(method, url, body?)、sleep(ms)。" +
                        "直接写 {变量} 会作为只读实时绑定（每次读取取当前值，set() 后用 get() 或再次读取）；" +
                        "模板字符串里只有 ${'$'}{...} 内的 {变量} 会展开。" +
                        "get/set 支持裸名与 rule./global./run./event. 前缀。脚本不能访问 Java 类、反射或文件系统。",
                    expansion = ParamExpansion.SCRIPT,
                    editor = ParamEditor.CODE),
                ParamSpec("timeout", "超时", ParamType.INTEGER, false, JsonPrimitive(10),
                    minValue = 1, maxValue = 60, suffix = "秒",
                    hint = "默认 10 秒；超时会中断脚本并把该动作标记为失败。"),
            ),
            outputs = listOf(
                out("script.result", "JS 返回值", VariableType.TEXT, "JS返回值", "脚本最后的 return 值的文本形式"),
            ), supports = normalLayer,
            description = "在沙箱中执行 JavaScript，返回值作为脚本输出。"),
        ComponentMeta("action.java_code", "执行 Java 代码", "脚本", ComponentKind.ACTION,
            listOf(
                ParamSpec("code", "代码", ParamType.MULTILINE_TEXT, required = true,
                    defaultValue = JsonPrimitive("host.log(\"hello\");\nreturn \"done\";"),
                    hint = "BeanShell 解释执行（Java 5 之前语法，不支持泛型/Lambda/Stream）。" +
                        "没有沙箱：代码以本应用权限运行，可读写文件、访问网络、执行命令。" +
                        "可用对象：context（Context）、host（getVariable/setVariable/clearVariable/log/showToast/notify/http/sleep）。" +
                        "变量请写 host.getVariable(\"名字\")，不要写 {名字}；超时 1–120 秒。",
                    expansion = ParamExpansion.SCRIPT,
                    editor = ParamEditor.CODE),
                ParamSpec("timeout", "超时", ParamType.INTEGER, required = false,
                    defaultValue = JsonPrimitive(30), minValue = 1, maxValue = 120, suffix = "秒",
                    hint = "默认 30 秒；超时后动作失败。纯死循环无法被强制终止，必要时只能结束应用进程。"),
            ),
            outputs = listOf(
                out("java.result", "Java 返回值", VariableType.TEXT, "Java返回值", "代码 return 值的文本形式"),
            ),
            supports = normalLayer,
            description = "在应用进程内执行 Java（BeanShell）代码，无沙箱隔离。"),
        ComponentMeta("action.http_request", "HTTP 请求", "网络", ComponentKind.ACTION,
            listOf(parameter("request", "请求", ParamType.MULTILINE_TEXT, "GET https://example.com", false,
                hint = "格式：GET <URL> 或 POST <URL> <内容>；GET 不带正文，POST 内容可换行。",
                expansion = ParamExpansion.HTTP_REQUEST)),
            outputs = listOf(
                out("http.body", "响应正文", VariableType.TEXT, "响应正文", "HTTP 响应正文，最多保留前 200 个字符"),
                out("http.status", "状态码", VariableType.INT, "状态码", "HTTP 状态码"),
            ), permissions = listOf("local_network"), supports = normalLayer),
        ComponentMeta("action.vibrate", "振动", "设备", ComponentKind.ACTION,
            listOf(parameter("duration", "时长", ParamType.DURATION, "500 毫秒", false,
                hint = "格式：非负数字 + 毫秒 / 秒 / 分钟。", durationMs = 500)), supports = normalLayer),
        ComponentMeta("action.flashlight", "手电筒", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("toggle"),
                ParameterCodec.switchOptions)), supports = normalLayer),
        ComponentMeta("action.ringer_mode", "响铃模式", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.ENUM, true, JsonPrimitive("ring"), listOf(
                ParamOption("ring", "响铃"), ParamOption("vibrate", "振动"), ParamOption("silent", "静音")),
                hint = "静音需要勿扰权限。")), supports = normalLayer),
        ComponentMeta("action.media_control", "媒体控制", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("action", "操作", ParamType.ENUM, true, JsonPrimitive("play"), listOf(
                ParamOption("play", "播放"), ParamOption("pause", "暂停"),
                ParamOption("previous", "上一首"), ParamOption("next", "下一首")))), supports = normalLayer),
        ComponentMeta("action.open_url", "打开网页", "网络", ComponentKind.ACTION,
            listOf(parameter("url", "网址", ParamType.TEXT, "https://example.com",
                hint = "格式：以 http:// 或 https:// 开头的网址，支持 %{变量}。")), supports = normalLayer),
        ComponentMeta("action.speak", "文字朗读", "设备", ComponentKind.ACTION,
            listOf(parameter("text", "内容", ParamType.MULTILINE_TEXT, "你好，流光", false,
                hint = "使用系统 TTS 朗读；朗读完成或 30 秒超时后进入下一步。")), supports = normalLayer),
        ComponentMeta("action.toast", "Toast 提示", "通知", ComponentKind.ACTION,
            listOf(parameter("text", "内容", ParamType.TEXT, "任务已完成", false),
                ParamSpec("duration", "显示时长", ParamType.ENUM, false, JsonPrimitive("short"), listOf(
                    ParamOption("short", "短"), ParamOption("long", "长")))), supports = normalLayer),
        ComponentMeta("action.screen_timeout", "屏幕超时", "设备", ComponentKind.ACTION,
            listOf(parameter("duration", "时长", ParamType.DURATION, "30 秒", false,
                hint = "范围 5 秒–30 分钟；需要修改系统设置权限。", durationMs = 30_000)),
            permissions = listOf("write_settings"), supports = rootAndNormalSupport("write_settings")),
        ComponentMeta("action.auto_rotate", "自动旋转", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"),
                ParameterCodec.switchOptions)), permissions = listOf("write_settings"),
            supports = rootAndNormalSupport("write_settings")),
        ComponentMeta("action.global_action", "系统导航", "设备", ComponentKind.ACTION,
            listOf(ParamSpec("action", "操作", ParamType.ENUM, true, JsonPrimitive("home"), listOf(
                ParamOption("back", "返回"), ParamOption("home", "主页"),
                ParamOption("recents", "最近任务"), ParamOption("lock", "锁屏"),
                ParamOption("screenshot", "截屏")))), permissions = listOf("a11y"),
            supports = rootAndNormalSupport("a11y")),
        ComponentMeta("action.airplane_mode", "飞行模式", "网络", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"),
                ParameterCodec.switchOptions)), minTier = Tier.NORMAL, supports = rootAndNormal),
        ComponentMeta("action.mobile_data", "移动数据", "网络", ComponentKind.ACTION,
            listOf(ParamSpec("mode", "模式", ParamType.TRI_STATE, false, JsonPrimitive("on"),
                ParameterCodec.switchOptions)), minTier = Tier.NORMAL, supports = rootAndNormal),
    )

    fun registerInto(registry: ComponentRegistry) = metadata.forEach(registry::register)
}
