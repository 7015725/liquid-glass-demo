package com.flowglass.demo.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.flowglass.demo.system.RootInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One command. Auto commands have triggers + conditions + actions;
 * one-tap commands only have actions and are fired manually.
 */
data class Cmd(
    val id: Long,
    val auto: Boolean,
    val ic: Ic,
    val title: String,
    val desc: String,
    val triggers: List<String> = emptyList(),
    val conditions: List<String> = emptyList(),
    val actions: List<String>,
    val category: Int = 0,
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val lastRun: String = "从未",
) {
    val meta: String
        get() = if (auto) "触发器 ${triggers.size} · 条件 ${conditions.size} · 任务 ${actions.size}"
        else "任务 ${actions.size} 项"
}

data class RunLog(val id: Long, val title: String, val reason: String, val time: String, val ok: Boolean, val ic: Ic)

data class MenuReq(val cmdId: Long, val auto: Boolean, val anchor: Offset)
data class UndoInfo(val cmd: Cmd, val index: Int)

private var nextId = 1000L
fun newId() = nextId++

private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

val AutoCategories = listOf("全部", "已开启", "定时", "通知", "位置", "设备", "网络")

private fun seedAuto() = listOf(
    Cmd(1, true, Ic.Location, "到公司自动连 WiFi", "工作日到达公司附近时，打开 WiFi 并切换到振动模式",
        listOf("进入地点：公司"), listOf("工作日 08:00–10:00"), listOf("打开 WiFi", "振动模式"), 4, pinned = true),
    Cmd(2, true, Ic.Bell, "验证码自动复制", "收到含「验证码」的短信或通知时，提取数字并复制到剪贴板",
        listOf("通知：包含「验证码」"), emptyList(), listOf("正则提取数字", "写入剪贴板", "弹出提示"), 3),
    Cmd(3, true, Ic.Moon, "夜间自动静音", "每天 23:00 开启勿扰，早上 7:00 自动恢复",
        listOf("时间：23:00"), listOf("非节假日"), listOf("开启勿扰", "亮度降到 10%"), 2),
    Cmd(4, true, Ic.Battery, "低电量省电", "电量低于 20% 且未充电时，关闭 5G、蓝牙并降低刷新率",
        listOf("电量 < 20%"), listOf("未在充电"), listOf("关闭 5G", "关闭蓝牙", "刷新率 60Hz"), 5),
    Cmd(5, true, Ic.Bluetooth, "耳机连上放音乐", "连接蓝牙耳机后自动打开音乐 App 继续播放",
        listOf("蓝牙已连接：耳机"), emptyList(), listOf("打开音乐", "媒体键：播放"), 5, enabled = false),
    Cmd(6, true, Ic.Wifi, "断网自动重连", "WiFi 断开超过 30 秒时自动重新连接，失败则切换移动数据",
        listOf("WiFi 断开"), listOf("持续 30 秒"), listOf("重连 WiFi", "打开移动数据"), 6),
    Cmd(7, true, Ic.Camera, "截图自动归档", "新截图按日期移动到「截图归档」相册",
        listOf("新文件：Screenshots"), emptyList(), listOf("移动文件", "通知结果"), 5, enabled = false),
    Cmd(8, true, Ic.Clock, "整点报时", "白天每个整点语音播报时间",
        listOf("时间：每小时 00 分"), listOf("08:00–22:00"), listOf("语音播报时间"), 2),
    Cmd(9, true, Ic.Shield, "陌生来电拦截", "未在通讯录中的号码在夜间来电时自动挂断并记录",
        listOf("来电"), listOf("号码不在通讯录", "22:00–07:00"), listOf("挂断", "写入日志"), 3),
)

private fun seedOneTap() = listOf(
    Cmd(101, false, Ic.Leaf, "一键省电", "关闭 5G、降低亮度、限制后台活动",
        actions = listOf("关闭 5G", "亮度 30%", "限制后台"), pinned = true, lastRun = "08:12"),
    Cmd(102, false, Ic.Moon, "一键勿扰", "静音并屏蔽通知 2 小时，结束后自动恢复",
        actions = listOf("勿扰模式", "2 小时后恢复"), pinned = true, lastRun = "昨天"),
    Cmd(103, false, Ic.Rocket, "清理后台", "结束除白名单外的后台应用",
        actions = listOf("强制停止后台应用", "释放内存"), lastRun = "07:40"),
    Cmd(104, false, Ic.Wifi, "开启热点", "打开热点并通过二维码分享密码",
        actions = listOf("打开热点", "显示二维码"), lastRun = "周日"),
    Cmd(105, false, Ic.Location, "发送定位", "把当前位置以短信发给家人",
        actions = listOf("获取定位", "生成地图链接", "发送短信")),
    Cmd(106, false, Ic.Camera, "扫码付款", "直接打开支付 App 的付款码页面",
        actions = listOf("打开付款码"), pinned = true, lastRun = "12:31"),
    Cmd(107, false, Ic.Terminal, "执行脚本", "以 Root 身份运行自定义 Shell 脚本",
        actions = listOf("su -c 脚本"), lastRun = "3 天前"),
)

@Stable
class AppState {
    // engine
    var running by mutableStateOf(true)
    var uptime by mutableIntStateOf(2 * 3600 + 41 * 60 + 7)
    var todayRuns by mutableIntStateOf(86)
    var mode by mutableIntStateOf(1)
    var accessibility by mutableStateOf(true)

    // lists
    val autoCmds = mutableStateListOf<Cmd>().apply { addAll(seedAuto()) }
    val oneTaps = mutableStateListOf<Cmd>().apply { addAll(seedOneTap()) }
    var autoFilter by mutableIntStateOf(0)
    val executing = mutableStateMapOf<Long, Int>() // id -> 1 running, 2 done
    val logs = mutableStateListOf(
        RunLog(-1, "验证码自动复制", "通知触发", "21:47", true, Ic.Bell),
        RunLog(-2, "一键省电", "手动执行", "20:15", true, Ic.Leaf),
        RunLog(-3, "断网自动重连", "WiFi 断开", "18:02", false, Ic.Wifi),
        RunLog(-4, "到公司自动连 WiFi", "进入地点", "08:56", true, Ic.Location),
    )

    // overlays
    var menu by mutableStateOf<MenuReq?>(null)
    var renaming by mutableStateOf<Cmd?>(null)
    var undo by mutableStateOf<UndoInfo?>(null)

    // settings
    var settingsPage by mutableIntStateOf(0)
    var root by mutableStateOf<RootInfo?>(null)

    fun listFor(auto: Boolean): MutableList<Cmd> = if (auto) autoCmds else oneTaps

    fun find(id: Long): Cmd? = autoCmds.firstOrNull { it.id == id } ?: oneTaps.firstOrNull { it.id == id }

    /** Display order: pinned first, otherwise original order. */
    fun sorted(auto: Boolean): List<Cmd> = listFor(auto).sortedByDescending { it.pinned }

    private fun update(id: Long, f: (Cmd) -> Cmd) {
        for (l in arrayOf(autoCmds, oneTaps)) {
            val i = l.indexOfFirst { it.id == id }
            if (i >= 0) { l[i] = f(l[i]); return }
        }
    }

    fun setEnabled(id: Long, on: Boolean) = update(id) { it.copy(enabled = on) }
    fun togglePin(id: Long) = update(id) { it.copy(pinned = !it.pinned) }
    fun rename(id: Long, title: String) = update(id) { it.copy(title = title.ifBlank { it.title }) }

    fun duplicate(id: Long) {
        val src = find(id) ?: return
        val l = listFor(src.auto)
        val i = l.indexOf(src)
        l.add(i + 1, src.copy(id = newId(), title = "${src.title} 副本", pinned = false, lastRun = "从未"))
    }

    fun delete(id: Long) {
        val src = find(id) ?: return
        val l = listFor(src.auto)
        val i = l.indexOf(src)
        l.removeAt(i)
        undo = UndoInfo(src, i)
    }

    fun undoDelete() {
        val u = undo ?: return
        val l = listFor(u.cmd.auto)
        l.add(u.index.coerceIn(0, l.size), u.cmd)
        undo = null
    }

    fun create(auto: Boolean): Cmd {
        val c = if (auto) Cmd(newId(), true, Ic.Bolt, "未命名指令", "点按编辑触发器、条件和任务",
            listOf("手动设置"), emptyList(), listOf("待添加"), enabled = false)
        else Cmd(newId(), false, Ic.Tap, "未命名一键指令", "点按编辑要执行的任务", actions = listOf("待添加"))
        listFor(auto).add(0, c)
        return c
    }

    fun execute(cmd: Cmd, scope: CoroutineScope, reason: String = "手动执行") {
        if (executing[cmd.id] == 1) return
        scope.launch {
            executing[cmd.id] = 1
            delay(900L + cmd.actions.size * 250L)
            executing[cmd.id] = 2
            todayRuns += 1
            val t = now()
            update(cmd.id) { it.copy(lastRun = t) }
            logs.add(0, RunLog(newId(), cmd.title, reason, t, true, cmd.ic))
            if (logs.size > 8) logs.removeAt(logs.lastIndex)
            delay(1600)
            executing.remove(cmd.id)
        }
    }
}
