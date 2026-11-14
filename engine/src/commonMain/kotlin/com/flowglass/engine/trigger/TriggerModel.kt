package com.flowglass.engine.trigger

import com.flowglass.engine.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One installable listener family. Several trigger types can share a source. */
enum class TriggerSource { TIME, SCREEN, POWER, BATTERY, BOOT, WIFI, BLUETOOTH, NOTIFICATION, APP, HEADSET }

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
    const val HEADSET = "trigger.headset"

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
        HEADSET -> TriggerSource.HEADSET
        else -> null
    }

    /** Trigger types that still have no listener. */
    val comingSoon: Set<String> = setOf(
        "trigger.location_enter", "trigger.location_exit",
    )
}

data class TriggerComponent(
    val typeId: String,
    val name: String,
    val params: Map<String, JsonElement> = emptyMap(),
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
    const val HEADSET = "headset"
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

    fun has(key: String): Boolean = values.containsKey(key)
    fun put(key: String, value: Boolean) { values[key] = value }
    fun text(key: String): String? = texts[key]
    fun putText(key: String, value: String) { texts[key] = value }
    fun removeMissing(keys: Set<String>) {
        values.keys.retainAll(keys)
        texts.keys.retainAll(keys)
    }
    fun clear() {
        values.clear()
        texts.clear()
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
            TriggerKinds.HEADSET -> matchesHeadset(binding, event, states)
            else -> false
        }
    }

    private fun matchesWifi(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
    ): Boolean {
        if (binding.component.typeId != TriggerTypes.WIFI) return false
        val connected = event.data["state"] == "connected"
        val wanted = binding.component.params["ssid"]?.stringValue().orEmpty().trim()
        val current = if (connected) event.data["ssid"].orEmpty().trim() else ""
        val previous = states.text(binding.key)
        states.putText(binding.key, current)
        if (previous == null) return false
        val matches = wanted.isEmpty() || current.equals(wanted, ignoreCase = true)
        return when (binding.component.params["state"]?.stringValue()) {
            "connected" -> connected && previous != current && matches
            "disconnected" -> !connected && (if (wanted.isEmpty()) previous.isNotEmpty()
                else previous.equals(wanted, ignoreCase = true))
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
        val actual = event.data["name"].orEmpty()
        if (wanted.isNotEmpty() && !actual.contains(wanted, ignoreCase = true)) return false
        val current = if (connected) actual.ifEmpty { "connected" } else ""
        val previous = states.text(binding.key)
        states.putText(binding.key, current)
        if (previous == null) return false
        return when (binding.component.params["state"]?.stringValue()) {
            "connected" -> connected && previous != current && current.isNotEmpty()
            "disconnected" -> !connected && (if (wanted.isEmpty()) previous.isNotEmpty()
                else previous.contains(wanted, ignoreCase = true))
            else -> false
        }
    }

    private fun matchesNotification(binding: TriggerBinding, event: TriggerEvent): Boolean {
        if (binding.component.typeId != TriggerTypes.NOTIFICATION) return false
        fun keyword(key: String) = binding.component.params[key]?.stringValue().orEmpty().trim()
        val app = keyword("app")
        if (app.isNotEmpty() && app != event.data["notification.app"].orEmpty()) return false
        val title = keyword("title")
        if (title.isNotEmpty() && !event.data["notification.title"].orEmpty().contains(title, ignoreCase = true)) return false
        val text = keyword("text")
        if (text.isNotEmpty() && !event.data["notification.text"].orEmpty().contains(text, ignoreCase = true)) return false
        return true
    }

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

    private fun matchesBattery(
        binding: TriggerBinding,
        event: TriggerEvent,
        states: TriggerStateStore,
        fallback: Int?,
    ): Boolean {
        val type = binding.component.typeId
        if (type != TriggerTypes.BATTERY_LEVEL && type != TriggerTypes.BATTERY_LOW_LEGACY) return false
        val level = event.data["battery"]?.toIntOrNull() ?: fallback ?: return false
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
