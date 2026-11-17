package com.flowglass.demo.system.exec

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Parses and validates a force-stop target before it reaches `su -c`. Only a single package
 * component and a numeric user id are allowed, so no shell metacharacters can be injected.
 */
internal data class ForceStopTarget(val packageName: String, val userId: Int) {
    val command: String = "am force-stop --user $userId $packageName"
}

internal object ForceStopGuard {
    const val SELF_PACKAGE = "com.flowglass.demo"
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    private val RESERVED = setOf("android", "com.android.systemui", SELF_PACKAGE)

    fun parse(raw: String, launcherPackages: Set<String> = emptySet()): ForceStopTarget {
        val text = raw.trim()
        require(text.isNotEmpty()) { "参数格式不正确：期望 应用包名 或 应用包名@userId" }
        val separator = text.indexOf('@')
        require(separator != 0 && text.indexOf('@', separator + 1) < 0) {
            "参数格式不正确：只允许一个 @userId 后缀"
        }
        val packageName = if (separator < 0) text else text.substring(0, separator)
        val userText = if (separator < 0) "0" else text.substring(separator + 1)
        require(PACKAGE_PATTERN.matches(packageName)) {
            "参数格式不正确：应用包名只能包含字母、数字、下划线和点"
        }
        val userId = userText.toIntOrNull()
        require(userId != null && userId in 0..999_999) {
            "参数格式不正确：userId 必须是 0–999999 的整数"
        }
        require(packageName !in RESERVED) { "不允许强制停止系统组件或本应用：$packageName" }
        require(packageName !in launcherPackages) { "不允许强制停止桌面启动器：$packageName" }
        return ForceStopTarget(packageName, userId)
    }

    fun launcherPackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }
}
