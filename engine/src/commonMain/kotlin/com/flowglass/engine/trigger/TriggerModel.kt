package com.flowglass.engine.trigger

import com.flowglass.engine.Clock
import com.flowglass.engine.MatchSpec
import com.flowglass.engine.OutputBinding
import com.flowglass.engine.RunMode
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One installable listener family. Several trigger types can share a source. */
enum class TriggerSource {
    TIME, SCREEN, POWER, BATTERY, BOOT, WIFI, BLUETOOTH, NOTIFICATION, APP, HEADSET, LOCATION,
    SYSTEM, METHOD_HOOK, BROADCAST, INTENT, HTTP, TILE, SHARE,
    PHONE, SMS, CALENDAR, MEDIA,
}

object TriggerTypes {
    const val TIME = "trigger.time"
    const val SCREEN_ON = "trigger.screen_on"
    const val SCREEN_OFF = "trigger.screen_off"
    const val USER_PRESENT = "trigger.user_present"
    const val POWER_CONNECTED = "trigger.power_connected"
    const val POWER_DISCONNECTED = "trigger.power_disconnected"
    const val BATTERY_LEVEL = "trigger.battery_level"
    const val BATTERY_LOW_LEGACY = "trigger.battery_low"
    const val CHARGING_LEGACY = "trigger.charging"
    const val BOOT_COMPLETED = "trigger.boot_completed"
    const val WIFI = "trigger.wifi"
    const val BLUETOOTH = "trigger.bluetooth"
    const val NOTIFICATION = "trigger.notification"
    const val APP_FOREGROUND = "trigger.app_foreground"
    const val APP_PROCESS = "trigger.app_process"
    const val APP_CRASH = "trigger.app_crash"
    const val KEYGUARD = "trigger.keyguard"
    const val MEDIA = "trigger.media"
    const val FOREGROUND_SERVICE = "trigger.foreground_service"
    const val HEADSET = "trigger.headset"
    const val LOCATION_ENTER = "trigger.location_enter"
    const val LOCATION_EXIT = "trigger.location_exit"
    const val APP_METHOD_CALL = "trigger.app_method_call"
    const val SYSTEM_TIME_CHANGED = "trigger.system_time_changed"
    const val NEXT_ALARM_CHANGED = "trigger.next_alarm_changed"
    const val STORAGE_MEDIA = "trigger.storage_media"
    const val BATTERY_FULL = "trigger.battery_full"
    const val BATTERY_OVERHEAT = "trigger.battery_overheat"
    const val SHUTDOWN = "trigger.shutdown"
    const val STORAGE_LOW = "trigger.storage_low"
    const val LOCALE_CHANGED = "trigger.locale_changed"
    const val PACKAGE_CHANGE = "trigger.package_change"
    const val WALLPAPER_CHANGED = "trigger.wallpaper_changed"
    const val SETTING_CHANGED = "trigger.setting_changed"
    const val TICK = "trigger.tick"
    const val ACCESSIBILITY_CHANGED = "trigger.accessibility_changed"
    const val INTENT_RECEIVED = "trigger.intent_received"
    const val HTTP_REQUEST = "trigger.http_request"
    const val QUICK_SETTING = "trigger.quick_setting"
    const val SHARE_RECEIVED = "trigger.share_received"
    const val CALL_STATE = "trigger.call_state"
    const val SMS_RECEIVED = "trigger.sms_received"
    const val MISSED_CALL = "trigger.missed_call"
    const val NOTIFICATION_REMOVED = "trigger.notification_removed"
    const val MEDIA_TRACK = "trigger.media_track"
    const val CALENDAR_CHANGED = "trigger.calendar_changed"

    /** Old rules keep their type id; only these two are mapped onto the new listeners. */
    fun sourceOf(typeId: String): TriggerSource? = when (typeId) {
        TIME -> TriggerSource.TIME
        SCREEN_ON, SCREEN_OFF, USER_PRESENT -> TriggerSource.SCREEN
        POWER_CONNECTED, POWER_DISCONNECTED, CHARGING_LEGACY -> TriggerSource.POWER
        BATTERY_LEVEL, BATTERY_LOW_LEGACY -> TriggerSource.BATTERY
        BOOT_COMPLETED -> TriggerSource.BOOT
        WIFI -> TriggerSource.WIFI
        BLUETOOTH -> TriggerSource.BLUETOOTH
        NOTIFICATION -> TriggerSource.NOTIFICATION
        APP_FOREGROUND -> TriggerSource.APP
        APP_PROCESS, APP_CRASH -> TriggerSource.SYSTEM
        KEYGUARD -> TriggerSource.SYSTEM
        MEDIA -> TriggerSource.SYSTEM
        FOREGROUND_SERVICE -> TriggerSource.SYSTEM
        HEADSET -> TriggerSource.HEADSET
        LOCATION_ENTER, LOCATION_EXIT -> TriggerSource.LOCATION
        APP_METHOD_CALL -> TriggerSource.METHOD_HOOK
        BATTERY_FULL, BATTERY_OVERHEAT -> TriggerSource.BATTERY
        SYSTEM_TIME_CHANGED, NEXT_ALARM_CHANGED, STORAGE_MEDIA, SHUTDOWN, STORAGE_LOW,
        LOCALE_CHANGED, PACKAGE_CHANGE, WALLPAPER_CHANGED, SETTING_CHANGED, TICK,
        ACCESSIBILITY_CHANGED -> TriggerSource.BROADCAST
        INTENT_RECEIVED -> TriggerSource.INTENT
        HTTP_REQUEST -> TriggerSource.HTTP
        QUICK_SETTING -> TriggerSource.TILE
        SHARE_RECEIVED -> TriggerSource.SHARE
        CALL_STATE, MISSED_CALL -> TriggerSource.PHONE
        SMS_RECEIVED -> TriggerSource.SMS
        NOTIFICATION_REMOVED -> TriggerSource.NOTIFICATION
        MEDIA_TRACK -> TriggerSource.MEDIA
        CALENDAR_CHANGED -> TriggerSource.CALENDAR
        else -> null
    }

    /** Trigger types that still have no listener. */
    val comingSoon: Set<String> = emptySet()
}

data class TriggerComponent(
    val typeId: String,
    val name: String,
    val params: Map<String, JsonElement> = emptyMap(),
    /** Instance-level output bindings copied from the command editor. */
    val outputs: List<OutputBinding> = emptyList(),
    /** 持久化组件 id；方法钩子用它拼出 hook id，保证事件只落到同一个触发器。 */
    val itemId: Long = 0,
)

/** A host-side, already-normalised view of one enabled automatic command. */
data class TriggerRule(
    val id: String,
    val cmdId: Long,
    val name: String,
    val enabled: Boolean,
    val triggers: List<TriggerComponent> = emptyList(),
    val conditions: List<TriggerComponent> = emptyList(),
    /** Android permission keys declared by the rule's triggers, conditions and actions. */
    val permissions: List<String> = emptyList(),
    /** Rule-level trigger conjunction; defaults to the historical ANY behavior. */
    val triggerMatch: MatchSpec = MatchSpec(),
    /** Rule-level repeat policy for a trigger firing while a run is active. */
    val runMode: RunMode = RunMode.DROP,
)

data class TriggerBinding(
    val rule: TriggerRule,
    val triggerIndex: Int,
    val component: TriggerComponent,
    val source: TriggerSource?,
) {
    val key: String get() = "${rule.id}#$triggerIndex"
}

object TriggerPlan {
    fun bindings(rule: TriggerRule): List<TriggerBinding> = rule.triggers.mapIndexed { index, component ->
        TriggerBinding(rule, index, component, TriggerTypes.sourceOf(component.typeId))
    }

    /** Reference-count input: only enabled rules contribute, and unsupported triggers never do. */
    fun requiredSources(rules: List<TriggerRule>): Set<TriggerSource> =
        rules.asSequence().filter { it.enabled }
            .flatMap { bindings(it).asSequence() }
            .mapNotNull { it.source }
            .toSet()

    fun timeBindings(rules: List<TriggerRule>): List<TriggerBinding> =
        rules.asSequence().filter { it.enabled }
            .flatMap { bindings(it).asSequence() }
            .filter { it.source == TriggerSource.TIME }
            .toList()
}

data class TriggerEvent(
    val kind: String,
    val source: TriggerSource,
    val atEpochMs: Long,
    val data: Map<String, String> = emptyMap(),
    /** Exact-binding target used by a scheduled alarm so repeated identical times stay distinct. */
    val targetRuleId: String? = null,
    val targetTriggerIndex: Int? = null,
    /**
     * User-named method-hook values. They are inserted verbatim (no `event.` prefix), which is
     * what makes `{订单号}` work while the legacy event keys stay available as `event.*`.
     */
    val methodHookVariables: Map<String, String> = emptyMap(),
    /** Monotonic dispatch sequence; the receiver uses gaps to count bounded-buffer drops. */
    val seq: Long = 0,
)

object TriggerKinds {
    const val TIME = "time"
    const val SCREEN_ON = "screen_on"
    const val SCREEN_OFF = "screen_off"
    const val USER_PRESENT = "user_present"
    const val POWER_CONNECTED = "power_connected"
    const val POWER_DISCONNECTED = "power_disconnected"
    const val BATTERY = "battery"
    const val BOOT_COMPLETED = "boot_completed"
    const val WIFI = "wifi"
    const val BLUETOOTH = "bluetooth"
    const val NOTIFICATION = "notification"
    const val APP = "app_foreground"
    const val APP_PROCESS_STARTED = "app_process_started"
    const val APP_PROCESS_ENDED = "app_process_ended"
    const val APP_CRASH = "app_crash"
    const val APP_ANR = "app_anr"
    const val KEYGUARD = "keyguard"
    const val MEDIA_PLAYING = "media_playing"
    const val MEDIA_PAUSED = "media_paused"
    const val MEDIA_STOPPED = "media_stopped"
    const val FOREGROUND_SERVICE_STARTED = "foreground_service_started"
    const val FOREGROUND_SERVICE_STOPPED = "foreground_service_stopped"
    const val HEADSET = "headset"
    const val LOCATION = "location"
    const val METHOD_CALL = "method_call"
    const val SYSTEM_TIME_CHANGED = "system_time_changed"
    const val NEXT_ALARM_CHANGED = "next_alarm_changed"
    const val STORAGE_MEDIA = "storage_media"
    const val BATTERY_FULL = "battery_full"
    const val BATTERY_OVERHEAT = "battery_overheat"
    const val SHUTDOWN = "shutdown"
    const val STORAGE_LOW = "storage_low"
    const val LOCALE_CHANGED = "locale_changed"
    const val PACKAGE_CHANGE = "package_change"
    const val WALLPAPER_CHANGED = "wallpaper_changed"
    const val SETTING_CHANGED = "setting_changed"
    const val TICK = "tick"
    const val ACCESSIBILITY_CHANGED = "accessibility_changed"
    const val INTENT_RECEIVED = "intent_received"
    const val HTTP_REQUEST = "http_request"
    const val QUICK_SETTING = "quick_setting"
    const val SHARE_RECEIVED = "share_received"
    const val CALL_STATE = "call_state"
    const val SMS_RECEIVED = "sms_received"
    const val MISSED_CALL = "missed_call"
    const val NOTIFICATION_REMOVED = "notification_removed"
    const val MEDIA_TRACK = "media_track"
    const val CALENDAR_CHANGED = "calendar_changed"
}

/** Recomputes the listener set and reports the delta; the host installs/removes only changes. */
class TriggerSourceRegistry(
    private val install: (TriggerSource) -> Unit,
    private val uninstall: (TriggerSource) -> Unit,
) {
    private val active = linkedSetOf<TriggerSource>()

    val registered: Set<TriggerSource> get() = active.toSet()

    fun update(required: Set<TriggerSource>): SourceDiff {
        val add = required.filterNot { it in active }
        val remove = active.filterNot { it in required }
        add.forEach(install)
        remove.forEach(uninstall)
        active += add
        active -= remove.toSet()
        return SourceDiff(add.toSet(), remove.toSet())
    }

    fun clear() {
        val remove = active.toList()
        remove.forEach(uninstall)
        active.clear()
    }
}

data class SourceDiff(val added: Set<TriggerSource>, val removed: Set<TriggerSource>)

/** Stateful triggers only fire on a false -> true transition; the first sample is the baseline. */
class TriggerStateStore {
    private val values = mutableMapOf<String, Boolean>()
    private val texts = mutableMapOf<String, String>()
    private val times = mutableMapOf<String, Long>()

    fun has(key: String): Boolean = values.containsKey(key)
    fun put(key: String, value: Boolean) { values[key] = value }
    fun text(key: String): String? = texts[key]
    fun putText(key: String, value: String) { texts[key] = value }
    fun removeMissing(keys: Set<String>) {
        values.keys.retainAll(keys)
        texts.keys.retainAll(keys)
        times.keys.retainAll(keys)
    }
    fun clear() {
        values.clear()
        texts.clear()
        times.clear()
    }

    /** Returns true only when the value changed from false to true. */
    fun crossed(key: String, current: Boolean): Boolean {
        val previous = values.put(key, current)
        return previous == false && current
    }

    /** Bidirectional edge: the first sample is the baseline and never fires. */
    fun changed(key: String, current: Boolean): Boolean {
        val previous = values.put(key, current)
        return previous != null && previous != current
    }

    /**
     * Time-based gate for event-shaped triggers that have no natural edge. Returns true at most
     * once per [windowMs]; the first call passes so a listener that just started is not silent.
     */
    fun cooldown(key: String, nowEpochMs: Long, windowMs: Long): Boolean {
        val previous = times[key]
        if (previous != null && nowEpochMs - previous < windowMs) return false
        times[key] = nowEpochMs
        return true
    }
}

object TriggerMatcher {
    fun matches(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
        batteryPercent: Int? = null,
    ): Boolean {
        if (binding.source == null) return false
        return when (event.kind) {
            TriggerKinds.SCREEN_ON -> binding.component.typeId == TriggerTypes.SCREEN_ON
            TriggerKinds.SCREEN_OFF -> binding.component.typeId == TriggerTypes.SCREEN_OFF
            TriggerKinds.USER_PRESENT -> binding.component.typeId == TriggerTypes.USER_PRESENT
            TriggerKinds.POWER_CONNECTED -> binding.component.typeId == TriggerTypes.POWER_CONNECTED ||
                (binding.component.typeId == TriggerTypes.CHARGING_LEGACY &&
                    binding.component.params["state"]?.stringValue() == "charging")
            TriggerKinds.POWER_DISCONNECTED -> binding.component.typeId == TriggerTypes.POWER_DISCONNECTED ||
                (binding.component.typeId == TriggerTypes.CHARGING_LEGACY &&
                    binding.component.params["state"]?.stringValue() == "stopped")
            TriggerKinds.BATTERY -> matchesBattery(binding, event, states, batteryPercent)
            TriggerKinds.BOOT_COMPLETED -> binding.component.typeId == TriggerTypes.BOOT_COMPLETED
            TriggerKinds.TIME -> matchesTime(binding, event)
            TriggerKinds.WIFI -> matchesWifi(binding, event, states)
            TriggerKinds.BLUETOOTH -> matchesBluetooth(binding, event, states)
            TriggerKinds.NOTIFICATION -> matchesNotification(binding, event)
            TriggerKinds.APP -> matchesApp(binding, event, states)
            TriggerKinds.APP_PROCESS_STARTED -> matchesProcessEvent(binding, event, "started")
            TriggerKinds.APP_PROCESS_ENDED -> matchesProcessEvent(binding, event, "ended")
            TriggerKinds.APP_CRASH -> matchesCrashEvent(binding, event, "crash")
            TriggerKinds.APP_ANR -> matchesCrashEvent(binding, event, "anr")
            TriggerKinds.KEYGUARD -> matchesKeyguard(binding, event)
            TriggerKinds.MEDIA_PLAYING -> matchesMedia(binding, event, "playing")
            TriggerKinds.MEDIA_PAUSED -> matchesMedia(binding, event, "paused")
            TriggerKinds.MEDIA_STOPPED -> matchesMedia(binding, event, "stopped")
            TriggerKinds.FOREGROUND_SERVICE_STARTED ->
                matchesForegroundService(binding, event, "started")
            TriggerKinds.FOREGROUND_SERVICE_STOPPED ->
                matchesForegroundService(binding, event, "stopped")
            TriggerKinds.HEADSET -> matchesHeadset(binding, event, states)
            TriggerKinds.LOCATION -> matchesLocation(binding, event, states)
            TriggerKinds.METHOD_CALL -> matchesMethodHook(binding, event)
            TriggerKinds.SYSTEM_TIME_CHANGED -> matchesFamily(
                binding, TriggerTypes.SYSTEM_TIME_CHANGED, event, "event",
            )
            TriggerKinds.NEXT_ALARM_CHANGED -> matchesNextAlarm(binding, event)
            TriggerKinds.STORAGE_MEDIA -> matchesFamily(
                binding, TriggerTypes.STORAGE_MEDIA, event, "event",
            )
            TriggerKinds.BATTERY_FULL -> matchesBatteryFull(binding, event, states)
            TriggerKinds.BATTERY_OVERHEAT -> matchesBatteryOverheat(binding, event, states)
            TriggerKinds.SHUTDOWN -> binding.component.typeId == TriggerTypes.SHUTDOWN
            TriggerKinds.STORAGE_LOW -> matchesStorageLow(binding, event, states)
            TriggerKinds.LOCALE_CHANGED -> binding.component.typeId == TriggerTypes.LOCALE_CHANGED
            TriggerKinds.PACKAGE_CHANGE -> matchesFamily(
                binding, TriggerTypes.PACKAGE_CHANGE, event, "event",
            )
            TriggerKinds.WALLPAPER_CHANGED -> binding.component.typeId == TriggerTypes.WALLPAPER_CHANGED
            TriggerKinds.SETTING_CHANGED -> matchesSetting(binding, event)
            TriggerKinds.TICK -> matchesTick(binding, event)
            TriggerKinds.ACCESSIBILITY_CHANGED ->
                binding.component.typeId == TriggerTypes.ACCESSIBILITY_CHANGED
            TriggerKinds.INTENT_RECEIVED -> matchesIntent(binding, event)
            TriggerKinds.HTTP_REQUEST -> matchesHttp(binding, event)
            TriggerKinds.QUICK_SETTING -> binding.component.typeId == TriggerTypes.QUICK_SETTING
            TriggerKinds.SHARE_RECEIVED -> matchesShare(binding, event)
            TriggerKinds.CALL_STATE -> matchesCallState(binding, event)
            TriggerKinds.SMS_RECEIVED -> matchesSms(binding, event)
            TriggerKinds.MISSED_CALL -> binding.component.typeId == TriggerTypes.MISSED_CALL
            TriggerKinds.NOTIFICATION_REMOVED -> matchesNotificationFields(
                binding, event, TriggerTypes.NOTIFICATION_REMOVED, removed = true,
            )
            TriggerKinds.MEDIA_TRACK -> matchesMediaTrack(binding, event)
            TriggerKinds.CALENDAR_CHANGED -> binding.component.typeId == TriggerTypes.CALENDAR_CHANGED
            else -> false
        }
    }

    /** True when the trigger describes a current state instead of a transient event. */
    fun isStateful(binding: TriggerBinding): Boolean = when (binding.component.typeId) {
        TriggerTypes.WIFI,
        TriggerTypes.CHARGING_LEGACY,
        TriggerTypes.POWER_CONNECTED,
        TriggerTypes.POWER_DISCONNECTED,
        TriggerTypes.BATTERY_LOW_LEGACY,
        -> true
        TriggerTypes.BATTERY_LEVEL ->
            binding.component.params["direction"]?.stringValue() != BATTERY_ANY
        else -> false
    }

    /** Evaluates a state trigger against the live environment, without consuming an edge. */
    fun matchesState(binding: TriggerBinding, environment: TriggerEnvironment): Boolean =
        when (binding.component.typeId) {
            TriggerTypes.WIFI -> {
                val connected = environment.networkType() == NetworkType.WIFI
                val wanted = binding.component.params["ssid"]?.stringValue().orEmpty().trim()
                val current = if (connected) environment.wifiSsid().orEmpty().trim() else ""
                // Without a readable SSID a connected network cannot be confirmed as the wanted one,
                // and it cannot be confirmed as disconnected either.
                val known = wanted.isEmpty() || !connected || current.isNotEmpty()
                when (binding.component.params["state"]?.stringValue()) {
                    "connected" -> connected && known &&
                        (wanted.isEmpty() || current.equals(wanted, ignoreCase = true))
                    "disconnected" -> if (wanted.isEmpty()) {
                        !connected
                    } else {
                        known && !current.equals(wanted, ignoreCase = true)
                    }
                    else -> false
                }
            }
            TriggerTypes.CHARGING_LEGACY -> {
                val charging = environment.isCharging()
                when (binding.component.params["state"]?.stringValue()) {
                    "charging" -> charging
                    "stopped" -> !charging
                    else -> false
                }
            }
            TriggerTypes.POWER_CONNECTED -> environment.isCharging()
            TriggerTypes.POWER_DISCONNECTED -> !environment.isCharging()
            TriggerTypes.SCREEN_ON -> environment.isScreenOn()
            TriggerTypes.SCREEN_OFF -> !environment.isScreenOn()
            TriggerTypes.BATTERY_LEVEL, TriggerTypes.BATTERY_LOW_LEGACY -> {
                if (binding.component.params["direction"]?.stringValue() == BATTERY_ANY) return false
                val level = environment.batteryPercent() ?: return false
                val threshold = binding.component.params["threshold"]?.intValueOrNull() ?: return false
                val below = binding.component.typeId == TriggerTypes.BATTERY_LOW_LEGACY ||
                    binding.component.params["direction"]?.stringValue() != "above"
                if (below) level < threshold else level > threshold
            }
            else -> false
        }

    private fun matchesWifi(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.WIFI) return false
        val connected = event.data["state"] == "connected"
        val wanted = binding.component.params["ssid"]?.stringValue().orEmpty().trim()
        val mode = binding.component.params["matchMode"]?.stringValue().orEmpty().ifEmpty { "exact" }
        val current = if (connected) event.data["ssid"].orEmpty().trim() else ""
        val previous = states.text(binding.key)
        states.putText(binding.key, current)
        if (previous == null) return false
        val matches = wanted.isEmpty() || textMatches(wanted, current, mode)
        return when (binding.component.params["state"]?.stringValue()) {
            "connected" -> connected && previous != current && matches
            "disconnected" -> !connected && (if (wanted.isEmpty()) previous.isNotEmpty()
                else textMatches(wanted, previous, mode))
            else -> false
        }
    }

    private fun matchesBluetooth(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.BLUETOOTH) return false
        val connected = event.data["state"] == "connected"
        val wanted = binding.component.params["name"]?.stringValue().orEmpty().trim()
        val mode = binding.component.params["matchMode"]?.stringValue().orEmpty().ifEmpty { "contains" }
        val actual = event.data["name"].orEmpty()
        if (wanted.isNotEmpty() && !textMatches(wanted, actual, mode)) return false
        val current = if (connected) actual.ifEmpty { "connected" } else ""
        val previous = states.text(binding.key)
        states.putText(binding.key, current)
        if (previous == null) return false
        return when (binding.component.params["state"]?.stringValue()) {
            "connected" -> connected && previous != current && current.isNotEmpty()
            "disconnected" -> !connected && (if (wanted.isEmpty()) previous.isNotEmpty()
                else textMatches(wanted, previous, mode))
            else -> false
        }
    }

    private fun matchesNotification(binding: TriggerBinding, event: TriggerEvent): Boolean =
        matchesNotificationFields(binding, event, TriggerTypes.NOTIFICATION, removed = false)

    private fun matchesApp(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.APP_FOREGROUND) return false
        val target = binding.component.params["package"]?.stringValue()?.trim().orEmpty()
        if (target.isEmpty()) return false
        val foreground = event.data["app"].orEmpty() == target
        val changed = states.changed(binding.key, foreground)
        if (!changed) return false
        return when (binding.component.params["state"]?.stringValue()) {
            "foreground" -> foreground
            "background" -> !foreground
            else -> false
        }
    }

    private fun matchesProcessEvent(
        binding: TriggerBinding,
        event: TriggerEvent,
        eventName: String,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.APP_PROCESS) return false
        if (binding.component.params["event"]?.stringValue() != eventName) return false
        val target = binding.component.params["package"]?.stringValue()?.trim().orEmpty()
        return target.isNotEmpty() && sameApp(target, event.data["app"].orEmpty())
    }

    private fun matchesCrashEvent(
        binding: TriggerBinding,
        event: TriggerEvent,
        eventName: String,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.APP_CRASH) return false
        if (binding.component.params["event"]?.stringValue() != eventName) return false
        val target = binding.component.params["package"]?.stringValue()?.trim().orEmpty()
        return target.isNotEmpty() && sameApp(target, event.data["app"].orEmpty())
    }

    private fun matchesKeyguard(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.KEYGUARD) return false
        val expected = binding.component.params["state"]?.stringValue() ?: return false
        return event.data["event"] == expected
    }

    private fun matchesMedia(binding: TriggerBinding, event: TriggerEvent, eventName: String): Boolean {
        if (binding.component.typeId != TriggerTypes.MEDIA) return false
        if (binding.component.params["state"]?.stringValue() != eventName) return false
        val target = binding.component.params["package"]?.stringValue()?.trim().orEmpty()
        return target.isNotEmpty() && sameApp(target, event.data["app"].orEmpty())
    }

    private fun matchesForegroundService(
        binding: TriggerBinding,
        event: TriggerEvent,
        eventName: String,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.FOREGROUND_SERVICE) return false
        if (binding.component.params["state"]?.stringValue() != eventName) return false
        val target = binding.component.params["package"]?.stringValue()?.trim().orEmpty()
        return target.isNotEmpty() && sameApp(target, event.data["app"].orEmpty())
    }

    /** The main profile may be written as a bare package or with an explicit @0 suffix. */
    private fun sameApp(target: String, actual: String): Boolean =
        target == actual ||
            target.endsWith("@0") && target.removeSuffix("@0") == actual ||
            actual.endsWith("@0") && actual.removeSuffix("@0") == target

    private fun matchesMethodHook(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.APP_METHOD_CALL) return false
        // 宿主已经校验过 hookId；这里再要求事件与这个触发器实例的 hook id 完全一致，
        // 否则同一应用里另一个方法的钩子可以把提取数据喂给这条规则。
        val expected = com.flowglass.engine.methodhook.AppMethodCall.hookId(
            binding.rule.cmdId, binding.component.itemId,
        )
        if (event.data["hook.id"] != expected) return false
        val target = binding.component.params["package"]?.stringValue()?.trim().orEmpty()
        if (target.isEmpty() || !sameApp(target, event.data["app"].orEmpty())) return false
        val className = binding.component.params["className"]?.stringValue()?.trim().orEmpty()
        if (className.isEmpty() || className != event.data["method.class"]) return false
        val methodName = binding.component.params["methodName"]?.stringValue()?.trim().orEmpty()
        if (methodName.isEmpty() || methodName != event.data["method.name"]) return false
        val wantedTypes = binding.component.params["paramTypes"]?.stringValue()?.trim().orEmpty()
        if (wantedTypes.isEmpty()) return true
        return wantedTypes.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() } ==
            event.data["method.parameterTypes"].orEmpty().split(',')
                .map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun matchesHeadset(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.HEADSET) return false
        val kind = binding.component.params["kind"]?.stringValue() ?: "any"
        val actual = event.data["kind"].orEmpty()
        if (kind != "any" && kind != actual) return false
        val plugged = event.data["state"] == "plugged"
        val changed = states.changed(binding.key, plugged)
        val expected = when (binding.component.params["state"]?.stringValue()) {
            "plugged" -> true
            "unplugged" -> false
            else -> return false
        }
        return changed && plugged == expected
    }

    private fun matchesLocation(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
    ): Boolean {
        val type = binding.component.typeId
        if (type != TriggerTypes.LOCATION_ENTER && type != TriggerTypes.LOCATION_EXIT) return false
        val latitude = event.data["lat"]?.toDoubleOrNull() ?: return false
        val longitude = event.data["lng"]?.toDoubleOrNull() ?: return false
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        val target = parseLocation(binding.component.params["location"]?.stringValue().orEmpty())
            ?: return false
        val radius = binding.component.params["radius"]?.intValueOrNull() ?: 500
        if (radius !in 100..5000) return false
        val inside = distanceMeters(latitude, longitude, target.first, target.second) <= radius
        if (!states.changed(binding.key, inside)) return false
        return if (type == TriggerTypes.LOCATION_ENTER) inside else !inside
    }

    private fun parseLocation(raw: String): Pair<Double, Double>? {
        val parts = raw.trim().split(Regex("[,，\\s]+")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * earthRadius * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun matchesBattery(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
        fallback: Int?,
    ): Boolean {
        val type = binding.component.typeId
        if (type != TriggerTypes.BATTERY_LEVEL && type != TriggerTypes.BATTERY_LOW_LEGACY) return false
        val level = event.data["battery"]?.toIntOrNull() ?: fallback ?: return false
        if (type == TriggerTypes.BATTERY_LEVEL &&
            binding.component.params["direction"]?.stringValue() == BATTERY_ANY
        ) {
            return states.cooldown(binding.key, event.atEpochMs, BATTERY_ANY_WINDOW_MS)
        }
        val threshold = binding.component.params["threshold"]?.intValueOrNull() ?: return false
        val below = when (type) {
            TriggerTypes.BATTERY_LOW_LEGACY -> true
            else -> when (binding.component.params["direction"]?.stringValue()) {
                "above" -> false
                else -> true
            }
        }
        val satisfied = if (below) level < threshold else level > threshold
        return states.crossed(binding.key, satisfied)
    }

    /** Family triggers share one type id and differ only by the `event` parameter. */
    private fun matchesFamily(
        binding: TriggerBinding,
        typeId: String,
        event: TriggerEvent,
        eventKey: String,
    ): Boolean {
        if (binding.component.typeId != typeId) return false
        val expected = binding.component.params[eventKey]?.stringValue()?.trim().orEmpty()
        if (expected.isEmpty() || expected == "any") return true
        return expected == event.data[eventKey]
    }

    private fun matchesNextAlarm(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.NEXT_ALARM_CHANGED) return false
        val wanted = binding.component.params["package"]?.stringValue()?.trim().orEmpty()
        if (wanted.isEmpty()) return true
        return wanted == event.data["alarm.package"].orEmpty()
    }

    private fun matchesBatteryFull(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.BATTERY_FULL) return false
        return states.crossed(binding.key, event.data["full"] == "true")
    }

    private fun matchesBatteryOverheat(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.BATTERY_OVERHEAT) return false
        val temperature = event.data["battery.temperature"]?.toIntOrNull() ?: return false
        val threshold = binding.component.params["temperature"]?.intValueOrNull() ?: return false
        return states.crossed(binding.key, temperature >= threshold)
    }

    private fun matchesStorageLow(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.STORAGE_LOW) return false
        val free = event.data["storage.freePercent"]?.toIntOrNull() ?: return false
        val threshold = binding.component.params["threshold"]?.intValueOrNull() ?: return false
        // The periodic host check re-samples a level, so a cooldown is more useful than an edge:
        // a device that is already low when automation starts still gets one alert.
        return free < threshold && states.cooldown(binding.key, event.atEpochMs, STORAGE_LOW_WINDOW_MS)
    }

    private fun matchesIntent(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.INTENT_RECEIVED) return false
        // The editor no longer exposes a token; old token parameters are accepted as legacy
        // data but deliberately ignored. The explicit receiver is the only entry boundary.
        val action = binding.component.params["action"]?.stringValue()?.trim().orEmpty()
        return action.isEmpty() || action == event.data["intent.action"]
    }

    private fun matchesHttp(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.HTTP_REQUEST) return false
        // Tokens are no longer part of the HTTP trigger contract; keep only request matching.
        val method = binding.component.params["method"]?.stringValue()?.trim().orEmpty()
        if (method.isNotEmpty() && method != "any" && !method.equals(event.data["http.method"], true)) {
            return false
        }
        val path = binding.component.params["path"]?.stringValue()?.trim().orEmpty()
        return path.isEmpty() || path == event.data["http.path"]
    }

    private fun matchesShare(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.SHARE_RECEIVED) return false
        val kind = binding.component.params["kind"]?.stringValue()?.trim().orEmpty()
        if (kind.isEmpty() || kind == "any") return true
        val mime = event.data["share.mime"].orEmpty()
        return when (kind) {
            "text" -> mime.startsWith("text/")
            "image" -> mime.startsWith("image/")
            else -> true
        }
    }

    private fun matchesCallState(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.CALL_STATE) return false
        val wanted = binding.component.params["event"]?.stringValue()?.trim().orEmpty()
        return wanted.isEmpty() || wanted == "any" || wanted == event.data["call.state"]
    }

    private fun matchesSms(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.SMS_RECEIVED) return false
        val sender = binding.component.params["sender"]?.stringValue()?.trim().orEmpty()
        if (sender.isNotEmpty() && sender != event.data["sms.sender"].orEmpty()) return false
        val keyword = binding.component.params["keyword"]?.stringValue()?.trim().orEmpty()
        val mode = binding.component.params["matchMode"]?.stringValue()?.trim().orEmpty().ifEmpty { "contains" }
        return keyword.isEmpty() || textMatches(keyword, event.data["sms.body"].orEmpty(), mode)
    }

    /** Literal substring by default; `exact` and `regex` are opt-in per trigger parameter. */
    private fun textMatches(pattern: String, text: String, mode: String): Boolean {
        if (pattern.isEmpty()) return true
        return when (mode) {
            "regex" -> runCatching {
                Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
            }.getOrDefault(false)
            "exact" -> text.equals(pattern, ignoreCase = true)
            else -> text.contains(pattern, ignoreCase = true)
        }
    }

    private fun matchesNotificationFields(
        binding: TriggerBinding,
        event: TriggerEvent,
        expectedType: String,
        removed: Boolean,
    ): Boolean {
        if (binding.component.typeId != expectedType) return false
        fun param(key: String) = binding.component.params[key]?.stringValue()?.trim().orEmpty()
        fun field(key: String) = event.data["notification.$key"].orEmpty()
        val app = param("app")
        if (app.isNotEmpty() && app != field("app")) return false
        val mode = param("matchMode").ifEmpty { "contains" }
        for (key in listOf("title", "text", "subtext")) {
            val wanted = param(key)
            if (wanted.isNotEmpty() && !textMatches(wanted, field(key), mode)) return false
        }
        val category = param("category")
        if (category.isNotEmpty() && category != field("category")) return false
        val channel = param("channel")
        if (channel.isNotEmpty() && channel != field("channel")) return false
        val ongoing = param("ongoing")
        if (ongoing.isNotEmpty() && ongoing != "any") {
            val expected = when (ongoing) {
                "yes" -> "true"
                "no" -> "false"
                else -> ongoing
            }
            if (expected != field("ongoing")) return false
        }
        // Kept as an explicit parameter so the caller and matcher cannot drift on removed-vs-posted.
        return if (removed) event.kind == TriggerKinds.NOTIFICATION_REMOVED
        else event.kind == TriggerKinds.NOTIFICATION
    }

    private fun matchesMediaTrack(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.MEDIA_TRACK) return false
        val app = binding.component.params["app"]?.stringValue()?.trim().orEmpty()
        return app.isEmpty() || app == event.data["media.package"].orEmpty()
    }

    private fun matchesSetting(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.SETTING_CHANGED) return false
        val namespace = binding.component.params["namespace"]?.stringValue()?.trim().orEmpty()
        if (namespace != event.data["setting.namespace"]) return false
        val key = binding.component.params["key"]?.stringValue()?.trim().orEmpty()
        return key.isNotEmpty() && key == event.data["setting.key"]
    }

    private fun matchesTick(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.TICK) return false
        if (event.targetRuleId != binding.rule.id) return false
        return event.targetTriggerIndex == null || event.targetTriggerIndex == binding.triggerIndex
    }

    private fun matchesTime(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.TIME) return false
        if (event.targetRuleId != null) {
            if (event.targetRuleId != binding.rule.id) return false
            if (event.targetTriggerIndex != null && event.targetTriggerIndex != binding.triggerIndex) return false
            return true
        }
        val expected = binding.component.params["time"]?.stringValue() ?: return false
        if (expected != event.data["time"]) return false
        val days = weekdaySet(binding.component.params["days"]?.stringValue()) ?: return true
        val offset = event.data["zone_offset_minutes"]?.toIntOrNull() ?: 0
        return isoWeekday(event.atEpochMs, offset) in days
    }

    fun weekdaySet(value: String?): Set<Int>? {
        if (value.isNullOrBlank()) return null
        return value.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
    }

    fun isoWeekday(epochMs: Long, zoneOffsetMinutes: Int): Int {
        val local = epochMs + zoneOffsetMinutes * 60_000L
        val day = floorDiv(local, DAY_MS)
        return ((day + 3) % 7 + 7).toInt() % 7 + 1
    }

    private const val DAY_MS = 86_400_000L
    private const val BATTERY_ANY = "any"
    private const val BATTERY_ANY_WINDOW_MS = 30_000L
    private const val STORAGE_LOW_WINDOW_MS = 30 * 60_000L
}

object TriggerSchedule {
    private const val DAY_MS = 86_400_000L

    /**
     * Next epoch for a wall-clock time. Uses the caller's current zone offset; a DST jump at the
     * target date can move the alarm by up to an hour, which the inexact fallback also tolerates.
     */
    fun nextOccurrence(
        nowEpochMs: Long,
        zoneOffsetMinutes: Int,
        time: String,
        weekdays: String?,
    ): Long? {
        val parts = time.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val days = TriggerMatcher.weekdaySet(weekdays) ?: (1..7).toSet()
        if (days.isEmpty()) return null
        val localNow = nowEpochMs + zoneOffsetMinutes * 60_000L
        val today = floorDiv(localNow, DAY_MS)
        val minuteOfDay = floorMod(localNow, DAY_MS) / 60_000L
        val targetMinute = hour * 60L + minute
        for (offset in 0..7L) {
            val day = today + offset
            val weekday = ((day + 3) % 7 + 7).toInt() % 7 + 1
            if (weekday !in days) continue
            if (offset == 0L && minuteOfDay >= targetMinute) continue
            val localTarget = day * DAY_MS + targetMinute * 60_000L
            return localTarget - zoneOffsetMinutes * 60_000L
        }
        return null
    }

    fun localTime(epochMs: Long, zoneOffsetMinutes: Int): String {
        val local = epochMs + zoneOffsetMinutes * 60_000L
        val minuteOfDay = floorMod(local, DAY_MS) / 60_000L
        return "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
    }
}

internal fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0L) q - 1 else q
}

internal fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

internal fun JsonElement.stringValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement.intValueOrNull(): Int? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
