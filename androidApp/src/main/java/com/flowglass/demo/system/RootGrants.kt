package com.flowglass.demo.system

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.flowglass.demo.system.exec.RootSessions

/**
 * Fixed root commands for the special accesses that otherwise require several system settings
 * screens. They are only executed from the explicit "用 Root 一键授予" action, never from a
 * trigger or an automatic scan. Every item is checked again through [Perms] after its commands
 * run, so a command that returns success but leaves the access disabled is reported as failed.
 */
internal data class RootGrantSpec(
    val key: String,
    val label: String,
    val commands: List<String>,
)

internal data class RootGrantResult(
    val key: String,
    val label: String,
    val success: Boolean,
    val detail: String,
)

internal object RootGrants {
    fun specs(context: Context): List<RootGrantSpec> = specs(
        packageName = context.packageName,
        accessibilityComponent = ComponentName(context, FlowAccessibilityService::class.java).flattenToString(),
        listenerComponent = ComponentName(
            context,
            com.flowglass.demo.system.trigger.FlowNotificationListener::class.java,
        ).flattenToString(),
        sdk = Build.VERSION.SDK_INT,
    )

    internal fun specs(
        packageName: String,
        accessibilityComponent: String,
        listenerComponent: String,
        sdk: Int,
    ): List<RootGrantSpec> {
        val pkg = packageName
        val a11y = accessibilityComponent
        val listener = listenerComponent
        val out = mutableListOf<RootGrantSpec>()

        if (sdk >= 33) out += RootGrantSpec(
            "notify", "通知",
            listOf("pm grant ${quote(pkg)} android.permission.POST_NOTIFICATIONS"),
        )
        out += RootGrantSpec("write_settings", "修改系统设置", listOf("appops set ${quote(pkg)} WRITE_SETTINGS allow"))
        out += RootGrantSpec("dnd", "勿扰权限", listOf("appops set ${quote(pkg)} ACCESS_NOTIFICATION_POLICY allow"))
        if (sdk >= 31) out += RootGrantSpec(
            "bluetooth_connect", "蓝牙连接",
            listOf("pm grant ${quote(pkg)} android.permission.BLUETOOTH_CONNECT"),
        )
        out += RootGrantSpec(
            "battery", "忽略电池优化",
            listOf("dumpsys deviceidle whitelist +${quote(pkg)}"),
        )
        out += RootGrantSpec(
            "a11y", "无障碍服务",
            listOf(enableAccessibility(a11y)),
        )
        out += RootGrantSpec(
            "notification_listener", "通知使用权",
            listOf("cmd notification allow_listener ${quote(listener)}"),
        )
        out += RootGrantSpec("usage", "使用情况访问", listOf("appops set ${quote(pkg)} GET_USAGE_STATS allow"))
        if (sdk >= 31) out += RootGrantSpec(
            "alarm", "精确闹钟",
            listOf("appops set ${quote(pkg)} SCHEDULE_EXACT_ALARM allow"),
        )
        out += RootGrantSpec("overlay", "悬浮窗", listOf("appops set ${quote(pkg)} SYSTEM_ALERT_WINDOW allow"))
        out += RootGrantSpec(
            "location", "位置信息",
            listOf(
                "pm grant ${quote(pkg)} android.permission.ACCESS_FINE_LOCATION",
                "pm grant ${quote(pkg)} android.permission.ACCESS_COARSE_LOCATION",
            ),
        )
        return out
    }

    suspend fun grant(context: Context, root: RootInfo): List<RootGrantResult> {
        check(root.granted) { "Root 未授权，无法执行辅助授权" }
        return specs(context).map { spec ->
            val item = Perms.all.firstOrNull { it.key == spec.key }
            if (item?.check(context) == true) {
                RootGrantResult(spec.key, spec.label, true, "已具备，无需修改")
            } else {
                val outputs = mutableListOf<String>()
                var commandFailed = false
                for (command in spec.commands) {
                    val result = runCatching {
                        RootSessions.run(root, command, timeoutMs = 15_000)
                    }.getOrElse { error ->
                        commandFailed = true
                        outputs += error.message ?: "Root 命令失败"
                        break
                    }
                    if (result.timedOut || result.truncated || result.exitCode != 0) {
                        commandFailed = true
                        outputs += result.combinedOutput.ifBlank { "退出码 ${result.exitCode}" }
                        break
                    }
                }
                val granted = item?.check(context) == true
                RootGrantResult(
                    spec.key,
                    spec.label,
                    granted,
                    when {
                        granted -> "已授予"
                        commandFailed -> outputs.lastOrNull()?.take(160) ?: "命令失败"
                        else -> "命令已执行，但权限检查仍未通过"
                    },
                )
            }
        }
    }

    private fun enableAccessibility(component: String): String {
        // This value comes from ComponentName (package/class, no shell metacharacters). Do not
        // single-quote it inside the double-quoted setting value: the quotes would be persisted
        // into enabled_accessibility_services and the accessibility permission check would fail.
        return "cur=\$(settings get secure enabled_accessibility_services); " +
            "case \":\$cur:\" in *\":$component:\"*) ;; *) " +
            "settings put secure enabled_accessibility_services \"\${cur:+\$cur:}$component\";; esac"
    }

    /** Package and component names come from the manifest, but quote them anyway. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
