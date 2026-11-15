package com.flowglass.demo.ui

import android.content.Context
import android.content.res.Configuration

/** User-selected appearance mode. SYSTEM follows the current configuration. */
enum class ThemeMode(val label: String, val description: String) {
    SYSTEM("跟随系统", "随系统深色模式自动切换"),
    LIGHT("浅色", "始终使用浅色外观"),
    DARK("深色", "始终使用深色外观"),
}

fun ThemeMode.resolveDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

fun Context.isSystemDark(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

object AppearanceSettings {
    private const val PREFS = "appearance"
    private const val KEY_THEME = "theme_mode"

    fun read(context: Context): ThemeMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
        return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
    }

    fun write(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
    }
}
