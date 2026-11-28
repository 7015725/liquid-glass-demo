package com.flowglass.demo.system

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.flowglass.demo.xposed.BroadcastRateLimiter
import com.flowglass.demo.xposed.HookIdentityReader
import com.flowglass.demo.xposed.SystemHookFamily
import com.flowglass.demo.xposed.SystemHookProtocol

/**
 * User-controlled switch, per-family subscription and handshake state for the system-framework
 * hook.
 *
 * The hook defaults to off. It announces that it is loaded with a protected broadcast; the app
 * replies with its persisted switch value, subscriptions and an
 * [SystemHookProtocol.EXTRA_APP_KNOWS_LOADED] flag. That flag lets the hook distinguish a normal
 * app update from an acknowledgement of the loaded announcement, so the two broadcasts cannot
 * form a loop.
 */
object SystemHookSettings {
    private const val PREFS = "system_hook"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LOADED = "hook_loaded"
    private const val KEY_HEARTBEAT = "last_heartbeat"
    private const val KEY_HOOKS = "hook_count"
    private const val KEY_AVAILABLE_FAMILIES = "available_families"
    private const val KEY_SUBSCRIBED_FAMILIES = "subscribed_families"
    private const val KEY_AUXILIARY_FAMILIES = "auxiliary_families"
    private const val KEY_WATCHED = "watched_packages"
    private const val HEARTBEAT_TTL_MS = 60_000L
    private const val HEARTBEAT_MIN_WRITE_MS = 2_000L
    private const val MAX_WATCHED = 128
    private const val SEND_MIN_INTERVAL_MS = 2_000L
    private val sendLimiter = BroadcastRateLimiter(SEND_MIN_INTERVAL_MS)
    private val retryHandler by lazy { Handler(Looper.getMainLooper()) }
    /** Test seam: the limiter keeps real epoch millis in production. */
    internal var clock: () -> Long = System::currentTimeMillis
    private val configRetryLock = Any()
    private var pendingConfigRetry: Runnable? = null
    private val retryLock = Any()
    private var pendingStateRetry: Runnable? = null

    enum class Status {
        /** No ACTION_LOADED has ever reached this app process. */
        NOT_LOADED,
        /** The hook is installed, but the user's switch is off. */
        LOADED_DISABLED,
        /** The switch is on, but no recent heartbeat has proved the hook is working. */
        LOADED_WAITING,
        /** A fresh heartbeat arrived but none of the expected detection points was installed. */
        NO_HOOKS,
        /** At least one subscribed family has no installed detection point on this ROM. */
        FAMILY_UNAVAILABLE,
        /** Loaded, enabled, heartbeat fresh, and every subscribed family is available. */
        READY,
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Whether the hook has announced itself to this app process (or sent a heartbeat). */
    fun isLoaded(context: Context): Boolean = prefs(context).getBoolean(KEY_LOADED, false)

    fun availableFamilies(context: Context): Int =
        prefs(context).getInt(KEY_AVAILABLE_FAMILIES, SystemHookFamily.defaultMask)

    fun subscribedFamilies(context: Context): Int {
        val store = prefs(context)
        return (store.getInt(KEY_SUBSCRIBED_FAMILIES, SystemHookFamily.defaultMask) or
            store.getInt(KEY_AUXILIARY_FAMILIES, 0)) and knownFamilyMask()
    }

    fun auxiliaryFamilies(context: Context): Int =
        prefs(context).getInt(KEY_AUXILIARY_FAMILIES, 0) and knownFamilyMask()

    fun watchedPackages(context: Context): Set<String> =
        parseWatched(prefs(context).getString(KEY_WATCHED, "").orEmpty())

    fun status(context: Context): Status {
        val store = prefs(context)
        return status(
            enabled = store.getBoolean(KEY_ENABLED, false),
            loaded = store.getBoolean(KEY_LOADED, false),
            heartbeatFresh = heartbeatFresh(store.getLong(KEY_HEARTBEAT, 0L)),
            hookCount = store.getInt(KEY_HOOKS, -1),
            availableFamilies = store.getInt(KEY_AVAILABLE_FAMILIES, SystemHookFamily.defaultMask),
            subscribedFamilies = subscribedFamilies(context),
        )
    }

    internal fun status(
        enabled: Boolean,
        loaded: Boolean,
        heartbeatFresh: Boolean,
        hookCount: Int,
        availableFamilies: Int = SystemHookFamily.defaultMask,
        subscribedFamilies: Int = SystemHookFamily.defaultMask,
    ): Status = when {
        !loaded -> Status.NOT_LOADED
        !enabled -> Status.LOADED_DISABLED
        !heartbeatFresh -> Status.LOADED_WAITING
        hookCount <= 0 -> Status.NO_HOOKS
        (subscribedFamilies and availableFamilies) != subscribedFamilies -> Status.FAMILY_UNAVAILABLE
        else -> Status.READY
    }

    fun isAlive(context: Context): Boolean = status(context) == Status.READY

    /**
     * Record the hook's protected "I am loaded" announcement. Older hooks do not send the family
     * mask; they are treated as foreground-only so an app update does not imply new capabilities.
     */
    fun markLoaded(
        context: Context,
        hookCount: Int,
        availableFamilies: Int = SystemHookFamily.defaultMask,
    ) {
        val store = prefs(context)
        val families = availableFamilies and knownFamilyMask()
        if (store.getBoolean(KEY_LOADED, false) &&
            store.getInt(KEY_HOOKS, -1) == hookCount &&
            store.getInt(KEY_AVAILABLE_FAMILIES, SystemHookFamily.defaultMask) == families
        ) return
        store.edit {
            putBoolean(KEY_LOADED, true)
            putInt(KEY_HOOKS, hookCount)
            putInt(KEY_AVAILABLE_FAMILIES, families)
        }
    }

    /** Returns true when this heartbeat changed the stored state and is worth acting on. */
    fun markHeartbeat(
        context: Context,
        hookCount: Int,
        availableFamilies: Int = SystemHookFamily.defaultMask,
    ): Boolean {
        val store = prefs(context)
        val now = System.currentTimeMillis()
        val families = availableFamilies and knownFamilyMask()
        val last = store.getLong(KEY_HEARTBEAT, 0L)
        if (store.getBoolean(KEY_LOADED, false) &&
            store.getInt(KEY_HOOKS, -1) == hookCount &&
            store.getInt(KEY_AVAILABLE_FAMILIES, SystemHookFamily.defaultMask) == families &&
            now - last in 0 until HEARTBEAT_MIN_WRITE_MS
        ) return false
        store.edit {
            putBoolean(KEY_LOADED, true)
            putLong(KEY_HEARTBEAT, now)
            putInt(KEY_HOOKS, hookCount)
            putInt(KEY_AVAILABLE_FAMILIES, families)
        }
        return true
    }

    private fun heartbeatFresh(last: Long): Boolean =
        last > 0L && System.currentTimeMillis() - last in 0..HEARTBEAT_TTL_MS

    fun setEnabled(context: Context, enabled: Boolean) {
        val store = prefs(context)
        store.edit {
            putBoolean(KEY_ENABLED, enabled)
            // Preserve the historical foreground behavior for an older install that has never
            // received a subscription from TriggerHost.
            if (enabled && !store.contains(KEY_SUBSCRIBED_FAMILIES)) {
                putInt(KEY_SUBSCRIBED_FAMILIES, SystemHookFamily.defaultMask)
            }
        }
        broadcastState(context)
    }

    /**
     * Persist the rules-derived subscription and send it to the hook. Called by TriggerHost when
     * the eligible rule set changes. A family is only requested while at least one enabled rule
     * uses it, so the hook does not collect events nobody listens to.
     */
    fun broadcastConfig(context: Context, families: Int, watched: Collection<String>) {
        val mask = (families and knownFamilyMask())
        val effective = (mask or auxiliaryFamilies(context)) and knownFamilyMask()
        val normalized = normalizeWatched(watched)
        prefs(context).edit {
            putInt(KEY_SUBSCRIBED_FAMILIES, mask)
            putString(KEY_WATCHED, normalized.joinToString(","))
        }
        if (!send(context, SystemHookProtocol.ACTION_CONFIG, effective, normalized)) {
            scheduleConfigRetry(context)
        }
    }

    /**
     * Subscribe to a family that belongs to a feature, not to the rule set. The clipboard
     * archive uses this so it keeps recording while TriggerService has no eligible rules.
     */
    internal fun setAuxiliaryFamily(context: Context, family: SystemHookFamily, enabled: Boolean) {
        val store = prefs(context)
        val current = store.getInt(KEY_AUXILIARY_FAMILIES, 0)
        val next = if (enabled) current or family.mask else current and family.mask.inv()
        store.edit { putInt(KEY_AUXILIARY_FAMILIES, next and knownFamilyMask()) }
        val mask = (store.getInt(KEY_SUBSCRIBED_FAMILIES, SystemHookFamily.defaultMask) or
            (next and knownFamilyMask())) and knownFamilyMask()
        val watched = parseWatched(store.getString(KEY_WATCHED, "").orEmpty())
        if (!send(context, SystemHookProtocol.ACTION_CONFIG, mask, watched)) {
            scheduleConfigRetry(context)
        }
    }

    /**
     * A→B→A within the two-second window: the trailing A carries the same content key as the
     * first A and would be dropped, leaving the hook on B while prefs say A. Re-send the persisted
     * config after the window so the last state always wins.
     */
    private fun scheduleConfigRetry(context: Context) {
        val appContext = context.applicationContext
        synchronized(configRetryLock) {
            if (pendingConfigRetry != null) return
            val task = object : Runnable {
                override fun run() {
                    synchronized(configRetryLock) { pendingConfigRetry = null }
                    val store = prefs(appContext)
                    val mask = store.getInt(KEY_SUBSCRIBED_FAMILIES, 0) and knownFamilyMask()
                    val effective = (mask or (store.getInt(KEY_AUXILIARY_FAMILIES, 0) and knownFamilyMask())) and knownFamilyMask()
                    val watched = parseWatched(store.getString(KEY_WATCHED, "").orEmpty())
                    if (!send(appContext, SystemHookProtocol.ACTION_CONFIG, effective, watched)) {
                        scheduleConfigRetry(appContext)
                    }
                }
            }
            pendingConfigRetry = task
            retryHandler.postDelayed(task, SEND_MIN_INTERVAL_MS)
        }
    }

    /** Clear subscriptions when the trigger host stops; the master switch is not changed. */
    fun clearSubscriptions(context: Context) {
        prefs(context).edit {
            putInt(KEY_SUBSCRIBED_FAMILIES, 0)
            putString(KEY_WATCHED, "")
        }
        if (!send(context, SystemHookProtocol.ACTION_CONFIG, subscribedFamilies(context), emptySet())) {
            scheduleConfigRetry(context)
        }
    }

    /**
     * Send the current switch state to the system hook. The loaded flag is part of the reply so a
     * hook that just announced itself does not announce again in response to this state. The
     * subscribed mask is repeated here so a hook that missed the preceding config still gets it.
     */
    fun broadcastState(context: Context) {
        val store = prefs(context)
        val mask = (store.getInt(KEY_SUBSCRIBED_FAMILIES, SystemHookFamily.defaultMask) or
            store.getInt(KEY_AUXILIARY_FAMILIES, 0)) and knownFamilyMask()
        val watched = parseWatched(store.getString(KEY_WATCHED, "").orEmpty())
        if (!send(context, SystemHookProtocol.ACTION_STATE, mask, watched)) {
            scheduleStateRetry(context)
        }
    }

    /**
     * A hook that restarted while the app process stayed alive may announce LOADED in the same
     * two-second window as our previous identical state. Dropping that reply would leave the new
     * hook without the switch value, so retry once after the window instead of bypassing the
     * limiter (which would reopen the flood path).
     */
    private fun scheduleStateRetry(context: Context) {
        val appContext = context.applicationContext
        synchronized(retryLock) {
            if (pendingStateRetry != null) return
            val task = object : Runnable {
                override fun run() {
                    synchronized(retryLock) { pendingStateRetry = null }
                    broadcastState(appContext)
                }
            }
            pendingStateRetry = task
            retryHandler.postDelayed(task, SEND_MIN_INTERVAL_MS)
        }
    }

    private fun send(
        context: Context,
        action: String,
        families: Int,
        watched: Collection<String>,
    ): Boolean {
        val enabled = isEnabled(context)
        val loaded = isLoaded(context)
        val normalizedWatched = watched.asSequence()
            .mapNotNull { HookIdentityReader.fromKey(it)?.key }
            .sorted()
            .joinToString(",")
        val key = "$action|$enabled|$loaded|$families|$normalizedWatched"
        if (!sendLimiter.allow(key, clock())) return false
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(action)
                    .setPackage("android")
                    .putExtra(SystemHookProtocol.EXTRA_ENABLED, enabled)
                    .putExtra(SystemHookProtocol.EXTRA_APP_KNOWS_LOADED, loaded)
                    .putExtra(SystemHookProtocol.EXTRA_FAMILIES, families)
                    .putExtra(SystemHookProtocol.EXTRA_WATCHED, normalizedWatched)
            )
        }
        return true
    }

    /** Test seam; production never needs to reset the rate limiter. */
    internal fun resetBroadcastThrottle() {
        val pending = synchronized(retryLock) {
            pendingStateRetry.also { pendingStateRetry = null }
        }
        if (pending != null) retryHandler.removeCallbacks(pending)
        val pendingConfig = synchronized(configRetryLock) {
            pendingConfigRetry.also { pendingConfigRetry = null }
        }
        if (pendingConfig != null) retryHandler.removeCallbacks(pendingConfig)
        sendLimiter.clear()
    }

    fun normalizeWatched(values: Collection<String>): Set<String> {
        val result = LinkedHashSet<String>()
        for (value in values) {
            val identity = HookIdentityReader.fromKey(value) ?: continue
            result += identity.key
            if (result.size >= MAX_WATCHED) break
        }
        return result
    }

    private fun parseWatched(raw: String): Set<String> =
        if (raw.isBlank()) emptySet()
        else raw.split(',').asSequence().take(MAX_WATCHED)
            .mapNotNull { HookIdentityReader.fromKey(it)?.key }
            .toSet()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun knownFamilyMask(): Int =
        SystemHookFamily.entries.fold(0) { result, family -> result or family.mask }
}
