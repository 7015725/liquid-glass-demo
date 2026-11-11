package com.flowglass.demo.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowglass.demo.data.RuleRepository
import com.flowglass.demo.system.RootInfo
import com.flowglass.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Presentation adapter. Every value comes from a saved rule, not demo samples. */
data class Cmd(val rule: Rule) {
    val id get() = rule.id
    val auto get() = rule.type == "auto"
    val title get() = rule.name
    val desc get() = rule.description.ifBlank { "点按编写 · ${if (auto) "自动指令" else "一键指令"}" }
    val triggers get() = rule.triggers.map(::componentLabel)
    val conditions get() = rule.conditions.map(::componentLabel)
    val actions get() = rule.actions.map(::componentLabel)
    val enabled get() = rule.enabled
    val pinned get() = rule.pinned
    val category: Int get() = when (rule.triggers.firstOrNull()?.let { ComponentRegistry.find(it.type)?.category }) {
        "时间" -> 2; "通知" -> 3; "位置" -> 4; "网络" -> 6; else -> 5
    }
    val ic: Ic get() = when (category) { 2 -> Ic.Clock; 3 -> Ic.Bell; 4 -> Ic.Location; 6 -> Ic.Wifi; else -> if (auto) Ic.Bolt else Ic.Tap }
    val meta get() = if (auto) "触发器 ${triggers.size} · 条件 ${conditions.size} · 动作 ${actions.size}" else "动作 ${actions.size} 项"
}
fun componentLabel(component: Component) = ComponentRegistry.find(component.type)?.name ?: component.type

data class MenuReq(val cmdId: String, val auto: Boolean, val anchor: Offset)
data class UndoInfo(val cmd: Cmd)
val AutoCategories = listOf("全部", "已启用", "定时", "通知", "位置", "设备", "网络")

/** IO lives in a ViewModel; the editor owns its configuration-restorable unsaved draft. */
class AppState(application: Application) : AndroidViewModel(application) {
    private val repository = RuleRepository(application)
    var rules by mutableStateOf<List<Rule>>(emptyList()); private set
    var globals by mutableStateOf<List<Variable>>(emptyList()); private set
    val autoCmds get() = rules.filter { it.type == "auto" }.map(::Cmd)
    val oneTaps get() = rules.filter { it.type == "manual" }.map(::Cmd)
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var editor by mutableStateOf<Rule?>(null)
    var showGlobals by mutableStateOf(false)
    var query by mutableStateOf("")
    var showSearch by mutableStateOf(false)
    var autoFilter by mutableIntStateOf(0)
    var menu by mutableStateOf<MenuReq?>(null)
    var renaming by mutableStateOf<Cmd?>(null)
    var undo by mutableStateOf<UndoInfo?>(null)
    var settingsPage by mutableIntStateOf(0)
    var mode by mutableIntStateOf(1)
    var root by mutableStateOf<RootInfo?>(null)
    var importPreview by mutableStateOf<RuleBundle?>(null)
    var importScriptConfirmed by mutableStateOf(false)
    var exportPayload: String? = null
    var exportRequested by mutableIntStateOf(0)
    var importRequested by mutableIntStateOf(0)

    init {
        viewModelScope.launch { repository.rules.collectLatest { rules = it } }
        viewModelScope.launch { repository.globals.collectLatest { globals = it } }
        viewModelScope.launch {
            try { repository.refresh(); loaded = true }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { message = "读取失败：${e.message}" }
        }
    }
    fun find(id: String) = rules.find { it.id == id }?.let(::Cmd)
    fun sorted(auto: Boolean) = (if (auto) autoCmds else oneTaps)
        .filter { query.isBlank() || it.title.contains(query, true) || it.desc.contains(query, true) }
        .sortedWith(compareByDescending<Cmd> { it.pinned }.thenBy { it.title })
    private fun write(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { message = failure.message ?: "操作失败，原数据已保留" }
            finally { busy = false }
        }
    }
    fun create(auto: Boolean) { editor = Rule(type = if (auto) "auto" else "manual") }
    fun edit(id: String) { editor = rules.find { it.id == id } }
    fun saveEditor(rule: Rule) = write {
        repository.save(rule.copy(enabled = false))
        editor = null
        message = "已保存 · 编写版不执行任务"
    }
    fun setEnabled(id: String, on: Boolean) {
        val rule = rules.find { it.id == id } ?: return
        if (on) message = "当前为编写版，执行端尚未接入；规则保持停用，可继续编辑与导出。"
        else write { repository.save(rule.copy(enabled = false)) }
    }
    fun togglePin(id: String) { rules.find { it.id == id }?.let { rule -> write { repository.save(rule.copy(pinned = !rule.pinned)) } } }
    fun rename(id: String, title: String) { rules.find { it.id == id }?.let { rule -> write { repository.save(rule.copy(name = title.trim())) } } }
    fun duplicate(id: String) {
        val rule = rules.find { it.id == id } ?: return
        write {
            val copy = RuleCodec.prepareImport(RuleBundle(rules = listOf(rule))).rules.single()
            repository.save(copy.copy(name = "${rule.name} 副本", pinned = false))
            message = "已复制，副本保持停用"
        }
    }
    fun delete(id: String) {
        val cmd = find(id) ?: return
        write { repository.delete(id); undo = UndoInfo(cmd) }
    }
    fun undoDelete() {
        val item = undo ?: return
        write { repository.save(item.cmd.rule); undo = null }
    }
    fun saveGlobal(variable: Variable) = write { repository.saveGlobal(variable); message = "全局变量已保存" }
    fun deleteGlobal(name: String) = write { repository.deleteGlobal(name); message = "全局变量已删除" }
    fun previewImport(text: String) {
        try { importPreview = RuleCodec.decodeBundle(text); importScriptConfirmed = false }
        catch (e: Exception) { message = "导入失败：${e.message}" }
    }
    fun commitImport() {
        val preview = importPreview ?: return
        if (RuleCodec.scriptWarnings(preview).isNotEmpty() && !importScriptConfirmed) {
            message = "请先查看并确认全部脚本内容"; return
        }
        write {
            repository.importBundle(RuleCodec.prepareImport(preview))
            importPreview = null
            importScriptConfirmed = false
            message = "已导入 ${preview.rules.size} 条指令，全部停用"
        }
    }
    fun requestExport(id: String? = null) {
        try {
            val selected = if (id == null) rules else rules.filter { it.id == id }
            require(selected.isNotEmpty() || globals.isNotEmpty()) { "没有可导出的指令或变量" }
            exportPayload = RuleCodec.exportBundle(selected, globals)
            exportRequested++
        } catch (e: Exception) { message = e.message ?: "导出准备失败" }
    }
}
