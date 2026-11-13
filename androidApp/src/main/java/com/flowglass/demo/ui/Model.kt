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
    val triggers: List<CommandItem> = emptyList(),
    val conditions: List<CommandItem> = emptyList(),
    val actions: List<CommandItem>,
    val category: Int = 0,
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val lastRun: String = "从未",
    val descriptionEdited: Boolean = true,
) {
    val meta: String
        get() = if (auto) "触发器 ${triggers.size} · 条件 ${conditions.size} · 任务 ${actions.size}"
        else "任务 ${actions.size} 项"
}

private fun Cmd.automaticDescription(): String {
    val tasks = actions.joinToString(" → ").ifEmpty { "请添加任务" }
    if (!auto) return tasks
    val trigger = triggers.joinToString(" 或 ").let { if (it.isEmpty()) "请添加触发器" else "$it 时" }
    val condition = conditions.joinToString(" 且 ").let { if (it.isEmpty()) "" else "，若 $it" }
    return "$trigger$condition，则 $tasks"
}

data class RunLog(val id: Long, val title: String, val reason: String, val time: String, val ok: Boolean, val ic: Ic,
    val detail: String = "", val steps: List<com.flowglass.demo.system.exec.StepResult> = emptyList())

data class MenuReq(val cmdId: Long, val auto: Boolean, val anchor: Offset)
data class UndoInfo(val cmd: Cmd, val index: Int)

private var nextId = 1000L
@Synchronized
fun newId(): Long {
    check(nextId < Long.MAX_VALUE) { "标识已用尽" }
    return nextId++
}

@Synchronized
private fun reserveIds(saved: PersistedAppState) {
    val maximum = ((saved.autoCmds + saved.oneTaps).flatMap { cmd ->
        listOf(cmd.id) + (cmd.triggers + cmd.conditions + cmd.actions).map { it.id }
    } + saved.logs.map { it.id }).maxOrNull() ?: 0L
    require(maximum < Long.MAX_VALUE) { "存储标识超出范围" }
    nextId = maxOf(nextId, maximum + 1)
}

private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

val AutoCategories = listOf("全部", "已开启", "定时", "通知", "位置", "设备", "网络")

private fun seedItems(vararg names: String) = names.map { display ->
    // Split only the explicit seed separator; keep times such as 23:00 intact.
    val name = display.substringBefore('：')
    val parameter = display.substringAfter('：', "")
    val catalogIcon = CommandSection.entries.asSequence()
        .flatMap { CommandCatalog.options(it).asSequence() }
        .firstOrNull { it.name == name }?.ic
    val icon = catalogIcon ?: when (name) {
        "通知" -> Ic.Bell
        "蓝牙已连接" -> Ic.Bluetooth
        "媒体键" -> Ic.Play
        "新文件" -> Ic.Camera
        else -> Ic.Bolt
    }
    val typeId = CommandSection.entries.asSequence().flatMap { CommandCatalog.options(it).asSequence() }
        .firstOrNull { it.name == name }?.typeId
    CommandItem(newId(), name, parameter, icon, typeId)
}

private fun seedAuto() = listOf(
    Cmd(1, true, Ic.Location, "到公司自动连 WiFi", "工作日到达公司附近时，打开 WiFi 并切换到振动模式",
        seedItems("进入地点：公司"), seedItems("工作日 08:00–10:00"), seedItems("开关 WiFi：开启", "振动模式"), 4, pinned = true),
    Cmd(2, true, Ic.Bell, "验证码自动复制", "收到含「验证码」的短信或通知时，提取数字并复制到剪贴板",
        seedItems("通知：包含「验证码」"), emptyList(), seedItems("正则提取数字", "写入剪贴板", "弹出提示"), 3),
    Cmd(3, true, Ic.Moon, "夜间自动静音", "手动测试会开启勿扰并将亮度调至 10%；自动触发与恢复尚未接入",
        seedItems("时间：23:00"), seedItems("非节假日"), seedItems("开关勿扰：开启", "调节亮度：10%"), 2),
    Cmd(4, true, Ic.Battery, "低电量省电", "电量低于 20% 且未充电时，关闭 5G、蓝牙并降低刷新率",
        seedItems("电量 < 20%"), seedItems("未在充电"), seedItems("关闭 5G", "开关蓝牙：关闭", "刷新率 60Hz"), 5),
    Cmd(5, true, Ic.Bluetooth, "耳机连上放音乐", "连接蓝牙耳机后自动打开音乐 App 继续播放",
        seedItems("蓝牙已连接：耳机"), emptyList(), seedItems("打开音乐", "媒体键：播放"), 5, enabled = false),
    Cmd(6, true, Ic.Wifi, "断网自动重连", "WiFi 断开超过 30 秒时自动重新连接，失败则切换移动数据",
        seedItems("WiFi 断开"), seedItems("持续 30 秒"), seedItems("重连 WiFi", "打开移动数据"), 6),
    Cmd(7, true, Ic.Camera, "截图自动归档", "新截图按日期移动到「截图归档」相册",
        seedItems("新文件：Screenshots"), emptyList(), seedItems("移动文件", "通知结果"), 5, enabled = false),
    Cmd(8, true, Ic.Clock, "整点报时", "白天每个整点语音播报时间",
        seedItems("时间：每小时 00 分"), seedItems("08:00–22:00"), seedItems("语音播报时间"), 2),
    Cmd(9, true, Ic.Shield, "陌生来电拦截", "未在通讯录中的号码在夜间来电时自动挂断并记录",
        seedItems("来电"), seedItems("号码不在通讯录", "22:00–07:00"), seedItems("挂断", "写入日志"), 3),
)

private fun seedOneTap() = listOf(
    Cmd(101, false, Ic.Leaf, "一键省电", "关闭 5G、降低亮度、限制后台活动",
        actions = seedItems("关闭 5G", "调节亮度：30%", "限制后台"), pinned = true),
    Cmd(102, false, Ic.Moon, "一键勿扰", "开启勿扰；定时恢复任务暂不支持，执行时会跳过",
        actions = seedItems("开关勿扰：开启", "2 小时后恢复"), pinned = true),
    Cmd(103, false, Ic.Rocket, "清理后台", "结束除白名单外的后台应用",
        actions = seedItems("强制停止后台应用", "释放内存")),
    Cmd(104, false, Ic.Wifi, "开启热点", "打开热点并通过二维码分享密码",
        actions = seedItems("打开热点", "显示二维码")),
    Cmd(105, false, Ic.Location, "发送定位", "把当前位置以短信发给家人",
        actions = seedItems("获取定位", "生成地图链接", "发送短信")),
    Cmd(106, false, Ic.Camera, "扫码付款", "直接打开支付 App 的付款码页面",
        actions = seedItems("打开付款码"), pinned = true),
    Cmd(107, false, Ic.Terminal, "执行脚本", "以 Root 身份运行示例命令 echo 流光，可在编辑页修改",
        actions = seedItems("执行 Shell(Root)：echo 流光")),
)

@Stable
class AppState internal constructor(saved: PersistedAppState?) {
    constructor() : this(null)
    private var executionEngine: com.flowglass.demo.system.exec.ExecutionEngine? = null
    fun bindExecutionContext(context: android.content.Context) {
        executionEngine = com.flowglass.demo.system.exec.ExecutionRuntime.engine(context)
    }
    internal fun bindExecutionEngine(engine: com.flowglass.demo.system.exec.ExecutionEngine) {
        executionEngine = engine
    }

    var editing by mutableStateOf<Long?>(null)
    var storageError by mutableStateOf<String?>(null)
    internal var engineRules by mutableStateOf(saved?.engineRules.orEmpty())
    internal var engineUi by mutableStateOf(saved?.engineUi ?: com.flowglass.engine.RuleUiMetadata())
    fun openEditor(id: Long) { if (find(id) != null) editing = id }
    fun closeEditor() {
        editing?.let { id ->
            update(id) { cmd ->
                if (cmd.title.isBlank()) cmd.copy(title = if (cmd.auto) "未命名指令" else "未命名一键指令") else cmd
            }
        }
        editing = null
    }
    fun setTitle(id: Long, text: String) = update(id) { it.copy(title = text) }
    fun setDescription(id: Long, text: String) = update(id) { it.copy(desc = text, descriptionEdited = true) }
    fun setIcon(id: Long, ic: Ic) = update(id) { it.copy(ic = ic) }
    fun addItem(cmdId: Long, section: CommandSection, option: CommandOption) {
        editItems(cmdId, section) { it + CommandItem(newId(), option.name, option.defaultParameter, option.ic, option.typeId) }
        if (section == CommandSection.TRIGGERS) update(cmdId) {
            if (it.auto && it.triggers.size == 1) it.copy(category = option.category) else it
        }
    }

    fun setItemParameter(cmdId: Long, section: CommandSection, itemId: Long, text: String) =
        editItems(cmdId, section) { items -> items.map { if (it.id == itemId) it.copy(parameter = text) else it } }

    fun moveItem(cmdId: Long, section: CommandSection, itemId: Long, delta: Int) =
        editItems(cmdId, section) { items ->
            val from = items.indexOfFirst { it.id == itemId }
            val to = from.toLong() + delta.toLong()
            if (from < 0 || to !in 0L until items.size.toLong()) items
            else items.toMutableList().apply { add(to.toInt(), removeAt(from)) }
        }

    fun removeItem(cmdId: Long, section: CommandSection, itemId: Long) =
        editItems(cmdId, section) { items -> items.filterNot { it.id == itemId } }

    private fun editItems(id: Long, section: CommandSection, change: (List<CommandItem>) -> List<CommandItem>) =
        update(id) { cmd ->
            if (!cmd.auto && section != CommandSection.ACTIONS) cmd
            else {
                val items = change(cmd.items(section))
                if (items == cmd.items(section)) return@update cmd
                val updated = when (section) {
                    CommandSection.TRIGGERS -> cmd.copy(triggers = items)
                    CommandSection.CONDITIONS -> cmd.copy(conditions = items)
                    CommandSection.ACTIONS -> cmd.copy(actions = items)
                }
                if (updated.descriptionEdited) updated else updated.copy(desc = updated.automaticDescription())
            }
        }

    // engine
    var running by mutableStateOf(saved?.running ?: true)
    var uptime by mutableIntStateOf(0)
    var todayRuns by mutableIntStateOf(0)
    var mode by mutableIntStateOf(saved?.mode ?: 1)
    var accessibility by mutableStateOf(true)

    // lists
    val autoCmds = mutableStateListOf<Cmd>().apply { addAll(saved?.autoCmds ?: seedAuto()) }
    val oneTaps = mutableStateListOf<Cmd>().apply { addAll(saved?.oneTaps ?: seedOneTap()) }
    var autoFilter by mutableIntStateOf(0)
    private val executionTokens = mutableMapOf<Long, Any>()
    val executing = mutableStateMapOf<Long, Int>() // id -> 1 running, 2 done, 3 failed
    val executionMessages = mutableStateMapOf<Long, String>()
    val logs = mutableStateListOf<RunLog>().apply { addAll(saved?.logs.orEmpty()) }

    init { if (saved != null) reserveIds(saved) }

    // search (null = closed)
    var query by mutableStateOf<String?>(null)

    fun matches(c: Cmd): Boolean {
        val q = query?.trim().orEmpty()
        return q.isEmpty() || c.title.contains(q, true) || c.desc.contains(q, true) ||
            (c.triggers + c.conditions + c.actions).any { it.display.contains(q, true) }
    }

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
        fun copied(items: List<CommandItem>) = items.map { it.copy(id = newId()) }
        l.add(i + 1, src.copy(id = newId(), title = "${src.title} 副本", pinned = false, lastRun = "从未",
            triggers = copied(src.triggers), conditions = copied(src.conditions), actions = copied(src.actions)))
    }

    fun delete(id: Long) {
        // Normalize the edited title before capturing the undo snapshot.
        if (editing == id) closeEditor()
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
        val c = Cmd(newId(), auto, if (auto) Ic.Bolt else Ic.Tap,
            if (auto) "未命名指令" else "未命名一键指令",
            if (auto) "请添加触发器和任务" else "请添加任务",
            actions = emptyList(), enabled = !auto, descriptionEdited = false)
        listFor(auto).add(0, c)
        return c
    }

    fun execute(cmd: Cmd, scope: CoroutineScope, reason: String = "手动执行"): kotlinx.coroutines.Job? {
        if (executing.containsKey(cmd.id)) return null
        val engine = executionEngine
        val token = Any()
        executionTokens[cmd.id] = token
        executionMessages.remove(cmd.id)
        executing[cmd.id] = 1
        return com.flowglass.demo.system.exec.ExecutionRuntime.scope.launch {
            try {
                val result = engine?.run(cmd, reason) ?: com.flowglass.demo.system.exec.RunResult(
                    cmd.id, reason, listOf(com.flowglass.demo.system.exec.StepResult(
                        cmd.id, "执行环境检查", com.flowglass.demo.system.exec.StepStatus.FAILURE,
                        "执行环境未就绪", 0)), 0)
                if (result.ignored) return@launch
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    executionMessages[cmd.id] = result.detail
                    executing[cmd.id] = if (result.ok) 2 else 3
                    todayRuns += 1
                    val t = now()
                    update(cmd.id) { it.copy(lastRun = t) }
                    logs.add(0, RunLog(newId(), cmd.title, reason, t, result.ok, cmd.ic, result.detail, result.steps.toList()))
                    if (logs.size > 8) logs.removeAt(logs.lastIndex)
                }
                delay(if (result.ok) 1600 else 2000)
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
                    if (executionTokens[cmd.id] === token) {
                        executionTokens.remove(cmd.id)
                        executing.remove(cmd.id)
                        executionMessages.remove(cmd.id)
                    }
                }
            }
        }
    }
}
