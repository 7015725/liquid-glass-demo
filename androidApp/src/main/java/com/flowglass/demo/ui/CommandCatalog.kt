package com.flowglass.demo.ui

enum class CommandSection { TRIGGERS, CONDITIONS, ACTIONS }
data class CommandItem(val id: Long, val name: String, val parameter: String, val ic: Ic) {
    val display: String get() = if (parameter.isEmpty()) name else "$name：$parameter"
    override fun toString() = display
}
data class CommandOption(val name: String, val ic: Ic, val defaultParameter: String, val category: Int = 0)
fun Cmd.items(section: CommandSection): List<CommandItem> = when (section) {
    CommandSection.TRIGGERS -> triggers
    CommandSection.CONDITIONS -> conditions
    CommandSection.ACTIONS -> actions
}
object CommandCatalog {
    private val triggers = listOf(
        CommandOption("时间", Ic.Clock, "08:00", 2),
        CommandOption("进入地点", Ic.Location, "公司", 4),
        CommandOption("离开地点", Ic.Location, "家", 4),
        CommandOption("收到通知", Ic.Bell, "验证码", 3),
        CommandOption("电量低于", Ic.Battery, "20%", 5),
        CommandOption("充电状态", Ic.Bolt, "开始充电", 5),
        CommandOption("蓝牙连接", Ic.Bluetooth, "耳机", 5),
        CommandOption("WiFi 连接/断开", Ic.Wifi, "连接家庭网络", 6),
        CommandOption("应用启动", Ic.Grid, "应用包名", 5),
        CommandOption("开机完成", Ic.Phone, "", 5),
    )
    private val conditions = listOf(
        CommandOption("时间段", Ic.Clock, "08:00–22:00"),
        CommandOption("星期", Ic.Clock, "工作日"),
        CommandOption("电量范围", Ic.Battery, "20%–80%"),
        CommandOption("网络类型", Ic.Wifi, "WiFi"),
        CommandOption("屏幕状态", Ic.Phone, "已亮屏"),
        CommandOption("勿扰状态", Ic.Moon, "已关闭"),
    )
    private val actions = listOf(
        CommandOption("开关 WiFi", Ic.Wifi, "开启"),
        CommandOption("开关蓝牙", Ic.Bluetooth, "开启"),
        CommandOption("开关勿扰", Ic.Moon, "开启"),
        CommandOption("调节亮度", Ic.Gauge, "50%"),
        CommandOption("调节音量", Ic.Gauge, "50%"),
        CommandOption("启动应用", Ic.Grid, "应用包名"),
        CommandOption("发送通知", Ic.Bell, "任务已完成"),
        CommandOption("复制到剪贴板", Ic.Copy, "待复制文本"),
        CommandOption("延时等待", Ic.Timer, "1 秒"),
        CommandOption("执行 Shell(Root)", Ic.Terminal, "echo 流光"),
        CommandOption("HTTP 请求", Ic.Refresh, "GET https://example.com"),
    )

    fun options(section: CommandSection): List<CommandOption> = when (section) {
        CommandSection.TRIGGERS -> triggers
        CommandSection.CONDITIONS -> conditions
        CommandSection.ACTIONS -> actions
    }
}
