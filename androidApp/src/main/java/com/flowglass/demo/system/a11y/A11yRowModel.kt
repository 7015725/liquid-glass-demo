package com.flowglass.demo.system.a11y

data class A11yRow(
    val component: String,
    val title: String,
    val subtitle: String,
    val selected: Boolean,
    val stateText: String,
    val ok: Boolean,
    val isSelf: Boolean,
)

/** Pure mapping from domain data to what the list shows. No Composable, no Context. */
object A11yRowModel {
    fun build(
        entries: List<A11yServiceEntry>,
        targets: Set<String>,
        statuses: Map<String, A11yKeepAliveSettings.ComponentStatus>,
    ): List<A11yRow> = entries.sortedWith(compareByDescending { it.isSelf }).map { entry ->
        val selected = entry.component in targets
        val status = statuses[entry.component]
        val (text, ok) = when {
            !entry.enabled -> "等待开启" to false
            !selected -> "未启用保活" to false
            status == null -> "未连接" to false
            else -> when (status.state) {
                A11yKeepAliveSettings.State.ACTIVE -> "运行中" to true
                A11yKeepAliveSettings.State.REPAIRED -> "已恢复" to true
                A11yKeepAliveSettings.State.REPAIRING -> "恢复中" to false
                A11yKeepAliveSettings.State.DISCONNECTED -> "已断开" to false
                A11yKeepAliveSettings.State.SKIPPED -> "跳过：${reasonText(status.reason)}" to false
                A11yKeepAliveSettings.State.LIMIT -> "已暂停" to false
                A11yKeepAliveSettings.State.UNSUPPORTED -> "不支持" to false
                A11yKeepAliveSettings.State.ERROR -> "错误" to false
            }
        }
        // A few system packages have no application label, so PackageManager falls back to the
        // package name. Showing the service label is more useful than repeating the package twice.
        val titled = entry.appLabel.takeIf { it != entry.packageName } ?: entry.serviceLabel
        A11yRow(
            component = entry.component,
            title = if (entry.isSelf) "流光自动化（本应用）" else titled,
            subtitle = buildString {
                if (titled != entry.serviceLabel) {
                    append(entry.serviceLabel)
                    append(" · ")
                }
                append(entry.packageName)
                if (!entry.enabled) append(" · 未启用")
            },
            selected = selected,
            stateText = text,
            ok = ok,
            isSelf = entry.isSelf,
        )
    }

    private fun reasonText(reason: String): String = when (reason) {
        "master_off" -> "系统无障碍已关闭"
        "component_removed" -> "已从系统设置移除"
        "app_stopped" -> "应用被强行停止"
        "already_bound" -> "已连接"
        "limit" -> "达到重试上限"
        "disabled" -> "总开关关闭"
        "not_target" -> "未选中"
        else -> reason.ifBlank { "跳过" }
    }
}
