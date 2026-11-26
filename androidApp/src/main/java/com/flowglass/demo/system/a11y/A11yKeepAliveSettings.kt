package com.flowglass.demo.system.a11y

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.flowglass.demo.xposed.ModuleStatus
import com.flowglass.demo.xposed.a11y.A11yKeepAliveProtocol

/**
 * Persisted user choices and the last reported state for every target.
 *
 * `targets` is a newline-joined string on purpose: the system-server side receives the same text
 * in a broadcast extra, and a StringSet would round-trip through `toString()` there.
 */
object A11yKeepAliveSettings {
    private const val PREFS = "a11y_keepalive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGETS = "targets"
    private const val KEY_BACKEND = "backend"
    private const val KEY_HOOK_LOADED = "hook_loaded"
    private const val KEY_HOOK_SUPPORTED = "hook_supported"
    private const val KEY_HOOK_COUNT = "hook_count"
    private const val KEY_STATUS = "status"
    private const val KEY_RESTORED = "restored_count"
    private const val KEY_KSU_INSTALLED = "ksu_installed"
    private const val MAX_TARGETS = 64
    private const val STATUS_TTL_MS = 7L * 24 * 60 * 60 * 1000

    const val BACKEND_AUTO = "auto"
    const val BACKEND_LSPOSED = "lsposed"
    const val BACKEND_ROOT = "root"

    private val COMPONENT = Regex("""^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+$""")

    enum class State(val wire: String) {
        ACTIVE("active"),
        DISCONNECTED("disconnected"),
        REPAIRING("repairing"),
        REPAIRED("repaired"),
        SKIPPED("skipped"),
        LIMIT("limit"),
        UNSUPPORTED("unsupported"),
        ERROR("error"),
        ;

        companion object {
            fun fromWire(value: String?): State? = entries.firstOrNull { it.wire == value }
        }
    }

    data class ComponentStatus(val state: State, val reason: String, val at: Long)

    enum class Backend { LSPOSED, ROOT_MODULE, NONE }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
        selfComponent: String = selfComponent(context),
        seed: Boolean = true,
    ) {
        val store = prefs(context)
        val firstEnable = enabled && !store.getBoolean(KEY_ENABLED, false)
        store.edit {
            putBoolean(KEY_ENABLED, enabled)
            // Only the transition into "on" may seed the default target, and only when the user has
            // never chosen anything. Toggling later must not resurrect a target the user removed.
            // `seed = false` is for the "tap a row while the switch is off" path: the row itself is
            // the user's choice, and seeding here would toggle that same row twice.
            if (seed && firstEnable && decodeTargets(store.getString(KEY_TARGETS, "")).isEmpty()) {
                putString(KEY_TARGETS, encodeTargets(setOf(selfComponent)))
            }
        }
        broadcastConfig(context)
    }

    fun targets(context: Context): Set<String> = decodeTargets(prefs(context).getString(KEY_TARGETS, ""))

    fun setTargets(context: Context, targets: Set<String>) {
        prefs(context).edit { putString(KEY_TARGETS, encodeTargets(targets)) }
        broadcastConfig(context)
    }

    /** Returns the new selection state for [component]. */
    fun toggleTarget(context: Context, component: String): Boolean {
        if (!COMPONENT.matches(component)) return false
        val current = targets(context)
        val selected = component !in current
        setTargets(context, if (selected) current + component else current - component)
        return selected
    }

    fun backend(context: Context): String =
        prefs(context).getString(KEY_BACKEND, BACKEND_AUTO) ?: BACKEND_AUTO

    fun setBackend(context: Context, backend: String) {
        prefs(context).edit { putString(KEY_BACKEND, backend) }
        broadcastConfig(context)
    }

    fun isKsuInstalled(context: Context): Boolean = prefs(context).getBoolean(KEY_KSU_INSTALLED, false)

    fun setKsuInstalled(context: Context, installed: Boolean) {
        prefs(context).edit { putBoolean(KEY_KSU_INSTALLED, installed) }
    }

    /** Auto = LSPosed when the hook is loaded and found candidates, else the root module. */
    fun resolvedBackend(context: Context): Backend = when (backend(context)) {
        BACKEND_LSPOSED -> if (ModuleStatus.isActive()) Backend.LSPOSED else Backend.NONE
        BACKEND_ROOT -> if (isKsuInstalled(context)) Backend.ROOT_MODULE else Backend.NONE
        else -> when {
            ModuleStatus.isActive() && isHookSupported(context) -> Backend.LSPOSED
            isKsuInstalled(context) -> Backend.ROOT_MODULE
            else -> Backend.NONE
        }
    }

    fun backendLabel(context: Context): String = when (resolvedBackend(context)) {
        Backend.LSPOSED -> "LSPosed 钩子"
        Backend.ROOT_MODULE -> "Root 模块"
        Backend.NONE -> "未启用"
    }

    fun isHookLoaded(context: Context): Boolean = prefs(context).getBoolean(KEY_HOOK_LOADED, false)
    fun isHookSupported(context: Context): Boolean = prefs(context).getBoolean(KEY_HOOK_SUPPORTED, false)
    fun hookCount(context: Context): Int = prefs(context).getInt(KEY_HOOK_COUNT, -1)

    fun markHookLoaded(context: Context, hooks: Int, supported: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_HOOK_LOADED, true)
            putBoolean(KEY_HOOK_SUPPORTED, supported)
            putInt(KEY_HOOK_COUNT, hooks)
        }
    }

    fun statuses(context: Context): Map<String, ComponentStatus> {
        val map = decodeStatus(prefs(context).getString(KEY_STATUS, ""))
        val now = System.currentTimeMillis()
        return map.filterValues { now - it.at <= STATUS_TTL_MS }
    }

    fun recordStatus(context: Context, component: String, state: State, reason: String, at: Long) {
        if (!COMPONENT.matches(component)) return
        val store = prefs(context)
        val next = decodeStatus(store.getString(KEY_STATUS, "")).toMutableMap()
        next[component] = ComponentStatus(state, reason.filter { it.isLetterOrDigit() || it == '_' }.take(32), at)
        val pruned = next.entries.sortedByDescending { it.value.at }.take(MAX_TARGETS).associate { it.key to it.value }
        store.edit { putString(KEY_STATUS, encodeStatus(pruned)) }
    }

    fun restoredCount(context: Context): Int = prefs(context).getInt(KEY_RESTORED, 0)

    fun bumpRestored(context: Context) {
        val store = prefs(context)
        store.edit { putInt(KEY_RESTORED, store.getInt(KEY_RESTORED, 0) + 1) }
    }

    internal fun encodeTargets(values: Set<String>): String = values.asSequence()
        .filter { COMPONENT.matches(it) }
        .distinct()
        .sorted()
        .take(MAX_TARGETS)
        .joinToString("\n")

    internal fun decodeTargets(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split('\n')
            .asSequence()
            .filter { COMPONENT.matches(it) }
            .take(MAX_TARGETS)
            .toSet()
    }

    internal fun encodeStatus(map: Map<String, ComponentStatus>): String = map.entries
        .sortedBy { it.key }
        .take(MAX_TARGETS)
        .joinToString(";") { (component, status) ->
            "$component|${status.state.wire}|${status.reason}|${status.at}"
        }

    internal fun decodeStatus(raw: String?): Map<String, ComponentStatus> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 4) return@mapNotNull null
            val state = State.fromWire(parts[1]) ?: return@mapNotNull null
            if (!COMPONENT.matches(parts[0])) return@mapNotNull null
            parts[0] to ComponentStatus(state, parts[2], parts[3].toLongOrNull() ?: 0L)
        }.toMap()
    }

    fun selfComponent(context: Context): String =
        ComponentName(context, com.flowglass.demo.system.FlowAccessibilityService::class.java).flattenToString()

    /** Pushes the switch and the selection to system_server. Not rate limited: the hook dedupes. */
    fun broadcastConfig(context: Context) {
        val intent = Intent(A11yKeepAliveProtocol.ACTION_CONFIG)
            .setPackage("android")
            .putExtra(A11yKeepAliveProtocol.EXTRA_ENABLED, isEnabled(context))
            .putExtra(A11yKeepAliveProtocol.EXTRA_TARGETS, encodeTargets(targets(context)))
            .putExtra(A11yKeepAliveProtocol.EXTRA_APP_KNOWS_LOADED, isHookLoaded(context))
        runCatching { context.applicationContext.sendBroadcast(intent) }
    }
}
