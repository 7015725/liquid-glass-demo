package com.flowglass.demo.system.trigger

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerPlan
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerSource
import com.flowglass.engine.trigger.TriggerTypes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runtime listeners for the system-broadcast trigger family. Everything here is registered while
 * the trigger service is alive; nothing is exported from the manifest, so a stopped app is silent
 * by design (Android will not deliver broadcasts to a force-stopped package either).
 *
 * The receiver is registered NOT_EXPORTED: every action below is a protected system broadcast, so
 * other apps cannot forge it, and the system UID is still allowed to deliver it.
 */
internal class SystemBroadcastSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val emit: (TriggerEvent) -> Unit,
    private val rules: () -> List<TriggerRule>,
) {
    private var receiver: BroadcastReceiver? = null
    private val observers = linkedMapOf<Uri, ContentObserver>()
    private var periodicJob: Job? = null
    private var periodicShape: String = ""
    private val lastTickAt = mutableMapOf<String, Long>()
    private var lastStorageCheckAt = 0L
    private var lastAccessibilityValue: String? = null

    fun start() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onBroadcast(intent)
            }
        }
        register(context, next, filter)
        receiver = next
    }

    fun sync(currentRules: List<TriggerRule>) {
        syncSettingObservers(currentRules)
        syncAccessibilityObserver(currentRules)
        syncPeriodic(currentRules)
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        observers.keys.toList().forEach(::unobserve)
        periodicJob?.cancel()
        periodicJob = null
        periodicShape = ""
        lastTickAt.clear()
        lastStorageCheckAt = 0L
        lastAccessibilityValue = null
    }

    private fun onBroadcast(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED -> emitTime("date")
            Intent.ACTION_TIME_CHANGED -> emitTime("time")
            Intent.ACTION_TIMEZONE_CHANGED -> emitTime("timezone")
            Intent.ACTION_LOCALE_CHANGED -> emit(
                TriggerEvent(
                    kind = TriggerKinds.LOCALE_CHANGED,
                    source = TriggerSource.BROADCAST,
                    atEpochMs = System.currentTimeMillis(),
                    data = mapOf("locale.tag" to Locale.getDefault().toLanguageTag()),
                ),
            )
            Intent.ACTION_WALLPAPER_CHANGED -> emitSimple(TriggerKinds.WALLPAPER_CHANGED)
            Intent.ACTION_SHUTDOWN -> emitSimple(TriggerKinds.SHUTDOWN)
            Intent.ACTION_MEDIA_MOUNTED -> emitMedia("mounted", intent)
            Intent.ACTION_MEDIA_UNMOUNTED -> emitMedia("unmounted", intent)
            Intent.ACTION_MEDIA_REMOVED -> emitMedia("removed", intent)
            Intent.ACTION_DEVICE_STORAGE_LOW -> emitStorageLow()
            Intent.ACTION_PACKAGE_ADDED -> emitPackage("added", intent)
            Intent.ACTION_PACKAGE_REMOVED -> emitPackage("removed", intent)
            Intent.ACTION_PACKAGE_REPLACED -> emitPackage("replaced", intent)
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> emitNextAlarm()
        }
    }

    private fun emitTime(kind: String) {
        emit(
            TriggerEvent(
                kind = TriggerKinds.SYSTEM_TIME_CHANGED,
                source = TriggerSource.BROADCAST,
                atEpochMs = System.currentTimeMillis(),
                data = mapOf("event" to kind),
            ),
        )
    }

    private fun emitSimple(kind: String) {
        emit(TriggerEvent(kind, TriggerSource.BROADCAST, System.currentTimeMillis()))
    }

    private fun emitMedia(event: String, intent: Intent) {
        emit(
            TriggerEvent(
                kind = TriggerKinds.STORAGE_MEDIA,
                source = TriggerSource.BROADCAST,
                atEpochMs = System.currentTimeMillis(),
                data = mapOf(
                    "event" to event,
                    "storage.path" to intent.data?.path.orEmpty(),
                ),
            ),
        )
    }

    private fun emitPackage(event: String, intent: Intent) {
        // An update is delivered as REMOVED(replacing) -> ADDED(replacing) -> REPLACED. Only
        // REPLACED is surfaced, otherwise one update would run the rule two or three times.
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        if (replacing) return
        val packageName = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        emit(
            TriggerEvent(
                kind = TriggerKinds.PACKAGE_CHANGE,
                source = TriggerSource.BROADCAST,
                atEpochMs = System.currentTimeMillis(),
                data = mapOf(
                    "event" to event,
                    "package.name" to packageName,
                    "package.label" to labelOf(packageName),
                ),
            ),
        )
    }

    private fun emitStorageLow() {
        val free = freePercent() ?: return
        emit(
            TriggerEvent(
                kind = TriggerKinds.STORAGE_LOW,
                source = TriggerSource.BROADCAST,
                atEpochMs = System.currentTimeMillis(),
                data = mapOf("storage.freePercent" to free.toString()),
            ),
        )
    }

    private fun emitNextAlarm() {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val info = manager?.nextAlarmClock
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        emit(
            TriggerEvent(
                kind = TriggerKinds.NEXT_ALARM_CHANGED,
                source = TriggerSource.BROADCAST,
                atEpochMs = System.currentTimeMillis(),
                data = mapOf(
                    "alarm.time" to (info?.let { formatter.format(Date(it.triggerTime)) } ?: ""),
                    "alarm.package" to (info?.showIntent?.creatorPackage ?: ""),
                ),
            ),
        )
    }

    private fun syncSettingObservers(currentRules: List<TriggerRule>) {
        val wanted = currentRules.any { rule ->
            rule.triggers.any { it.typeId == TriggerTypes.SETTING_CHANGED }
        }
        val targets = listOf("system" to Settings.System.CONTENT_URI) +
            listOf("secure" to Settings.Secure.CONTENT_URI) +
            listOf("global" to Settings.Global.CONTENT_URI)
        if (!wanted) {
            targets.forEach { (_, uri) -> unobserve(uri) }
            return
        }
        targets.forEach { (namespace, uri) ->
            observe(uri, notifyForDescendants = true) { changed ->
                val key = changed?.lastPathSegment?.takeIf { it.isNotEmpty() && it != namespace }
                    ?: return@observe
                val value = readSetting(namespace, key).orEmpty()
                emit(
                    TriggerEvent(
                        kind = TriggerKinds.SETTING_CHANGED,
                        source = TriggerSource.BROADCAST,
                        atEpochMs = System.currentTimeMillis(),
                        data = mapOf(
                            "setting.namespace" to namespace,
                            "setting.key" to key,
                            "setting.value" to value,
                        ),
                    ),
                )
            }
        }
    }

    private fun syncAccessibilityObserver(currentRules: List<TriggerRule>) {
        val wanted = currentRules.any { rule ->
            rule.triggers.any { it.typeId == TriggerTypes.ACCESSIBILITY_CHANGED }
        }
        val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (!wanted) {
            unobserve(uri)
            lastAccessibilityValue = null
            return
        }
        observe(uri, notifyForDescendants = false) {
            val raw = readSetting("secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (raw == lastAccessibilityValue) return@observe
            lastAccessibilityValue = raw
            val services = raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
            emit(
                TriggerEvent(
                    kind = TriggerKinds.ACCESSIBILITY_CHANGED,
                    source = TriggerSource.BROADCAST,
                    atEpochMs = System.currentTimeMillis(),
                    data = mapOf(
                        "accessibility.count" to services.size.toString(),
                        "accessibility.services" to services.joinToString(","),
                    ),
                ),
            )
        }
    }

    private fun syncPeriodic(currentRules: List<TriggerRule>) {
        val bindings = periodicBindings(currentRules)
        val tickKeys = bindings.filter { it.component.typeId == TriggerTypes.TICK }.map { it.key }.sorted()
        val lowKeys = bindings.filter { it.component.typeId == TriggerTypes.STORAGE_LOW }.map { it.key }.sorted()
        val shape = tickKeys.joinToString(",") + "|" + lowKeys.joinToString(",")
        if (shape == periodicShape && periodicJob?.isActive == true) return
        periodicShape = shape
        periodicJob?.cancel()
        lastTickAt.keys.retainAll(tickKeys.toSet())
        if (bindings.isEmpty()) {
            periodicJob = null
            return
        }
        periodicJob = scope.launch {
            while (isActive) {
                val live = periodicBindings(rules())
                val ticks = live.filter { it.component.typeId == TriggerTypes.TICK }
                val low = live.filter { it.component.typeId == TriggerTypes.STORAGE_LOW }
                delay(if (ticks.isNotEmpty()) 1_000L else 60_000L)
                val now = System.currentTimeMillis()
                for (binding in ticks) {
                    val seconds = runCatching {
                        binding.component.params["interval"]?.jsonPrimitive?.intOrNull
                    }.getOrNull()?.coerceIn(MIN_TICK_SECONDS, MAX_TICK_SECONDS)
                        ?: DEFAULT_TICK_SECONDS
                    val intervalMs = seconds * 1_000L
                    val previous = lastTickAt[binding.key]
                    if (previous == null) {
                        lastTickAt[binding.key] = now
                        continue
                    }
                    if (now - previous < intervalMs) continue
                    lastTickAt[binding.key] = now
                    emit(
                        TriggerEvent(
                            kind = TriggerKinds.TICK,
                            source = TriggerSource.BROADCAST,
                            atEpochMs = now,
                            data = mapOf("tick.interval" to seconds.toString()),
                            targetRuleId = binding.rule.id,
                            targetTriggerIndex = binding.triggerIndex,
                        ),
                    )
                }
                if (low.isNotEmpty() && now - lastStorageCheckAt >= STORAGE_CHECK_MS) {
                    lastStorageCheckAt = now
                    val free = freePercent() ?: continue
                    emit(
                        TriggerEvent(
                            kind = TriggerKinds.STORAGE_LOW,
                            source = TriggerSource.BROADCAST,
                            atEpochMs = now,
                            data = mapOf("storage.freePercent" to free.toString()),
                        ),
                    )
                }
            }
        }
    }

    private fun periodicBindings(currentRules: List<TriggerRule>): List<TriggerBinding> =
        currentRules.asSequence()
            .filter { it.enabled }
            .flatMap { TriggerPlan.bindings(it).asSequence() }
            .filter {
                it.source == TriggerSource.BROADCAST &&
                    (it.component.typeId == TriggerTypes.TICK ||
                        it.component.typeId == TriggerTypes.STORAGE_LOW)
            }
            .toList()

    private fun observe(uri: Uri, notifyForDescendants: Boolean, onChange: (Uri?) -> Unit) {
        if (observers.containsKey(uri)) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChange(uri)
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(uri, notifyForDescendants, observer)
            true
        }.getOrDefault(false)
        if (registered) observers[uri] = observer
    }

    private fun unobserve(uri: Uri) {
        observers.remove(uri)?.let { observer ->
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    private fun readSetting(namespace: String, key: String): String? = runCatching {
        val resolver: ContentResolver = context.contentResolver
        when (namespace) {
            "system" -> Settings.System.getString(resolver, key)
            "secure" -> Settings.Secure.getString(resolver, key)
            "global" -> Settings.Global.getString(resolver, key)
            else -> null
        }
    }.getOrNull()

    private fun labelOf(packageName: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault("")

    private fun freePercent(): Int? = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        val total = stat.totalBytes
        if (total <= 0L) null else ((stat.availableBytes * 100L) / total).toInt().coerceIn(0, 100)
    }.getOrNull()

    private companion object {
        const val MIN_TICK_SECONDS = 5
        const val MAX_TICK_SECONDS = 86_400
        const val DEFAULT_TICK_SECONDS = 30
        const val STORAGE_CHECK_MS = 60_000L
    }
}
