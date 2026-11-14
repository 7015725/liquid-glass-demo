package com.flowglass.demo.system.trigger

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flowglass.demo.system.Perms
import com.flowglass.demo.ui.Cmd
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.trigger.TriggerTypes

enum class RuleListenState(val label: String) {
    LISTENING("监听中"),
    PAUSED("引擎已暂停"),
    MISSING_PERMISSION("缺少权限"),
    DISABLED("未启用"),
    UNSUPPORTED("暂不支持"),
    UNCONFIGURED("未配置触发器"),
}

/** Live values published by the foreground service; the UI only reads them. */
object TriggerStatus {
    var serviceActive by mutableStateOf(false)
    var listeningRules by mutableIntStateOf(0)
    var registeredSources by mutableIntStateOf(0)
    var listeningRuleIds by mutableStateOf<Set<Long>>(emptySet())
    var exactAlarmAllowed by mutableStateOf(true)

    fun reset() {
        serviceActive = false
        listeningRules = 0
        registeredSources = 0
        listeningRuleIds = emptySet()
    }
}

private val registry = BuiltInComponents.registry()

fun Cmd.listenState(running: Boolean, context: Context): RuleListenState = when {
    !enabled -> RuleListenState.DISABLED
    !running -> RuleListenState.PAUSED
    triggers.isEmpty() -> RuleListenState.UNCONFIGURED
    triggers.none { TriggerTypes.sourceOf(it.typeId.orEmpty()) != null } -> RuleListenState.UNSUPPORTED
    hasMissingPermission(context) -> RuleListenState.MISSING_PERMISSION
    !TriggerStatus.serviceActive || id !in TriggerStatus.listeningRuleIds -> RuleListenState.PAUSED
    else -> RuleListenState.LISTENING
}

/** Only permissions the app can actually request and that this rule depends on block listening. */
private fun Cmd.hasMissingPermission(context: Context): Boolean = (triggers + conditions + actions).any { item ->
    val meta = item.typeId?.let(registry::find) ?: return@any false
    meta.permissions.any { key ->
        Perms.all.firstOrNull { it.key == key }?.check(context)?.not() == true
    }
}
