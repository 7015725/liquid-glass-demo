package com.flowglass.demo.system.clipboard

import android.content.ClipboardManager
import android.content.Context
import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.xposed.SystemHookFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

internal object ClipboardArchive {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = AtomicReference<Context?>(null)
    private val storeRef = AtomicReference<ClipboardHistoryStore?>(null)
    private val suppressed = AtomicReference<Pair<String, Long>?>(null)
    @Volatile private var listenerRegistered = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val context = appContext.get() ?: return@OnPrimaryClipChangedListener
        if (!ClipboardSettings.isEnabled(context)) return@OnPrimaryClipChangedListener
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return@OnPrimaryClipChangedListener
        val payload = ClipboardPayloadReader.fromClip(
            clip = runCatching { manager.primaryClip }.getOrNull(),
            packageName = "",
            userId = 0,
            source = ClipboardCaptureSource.FOREGROUND,
            atEpochMs = System.currentTimeMillis(),
        ) ?: return@OnPrimaryClipChangedListener
        recordAndDispatch(context, payload)
    }

    private fun recordAndDispatch(context: Context, payload: ClipboardPayload) {
        scope.launch {
            val recorded = recordForTest(payload, store(context))
            if (recorded) {
                kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                    ClipboardTriggerDispatch.deliver(context, payload)
                }
            }
        }
    }

    fun sync(context: Context) {
        val app = context.applicationContext
        appContext.set(app)
        if (ClipboardSettings.isEnabled(app)) install(app) else uninstall(app)
    }

    private fun install(context: Context) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        if (!listenerRegistered) {
            manager.addPrimaryClipChangedListener(listener)
            listenerRegistered = true
        }
        // The auxiliary family is independent of the trigger rule set. This is what keeps
        // clipboard capture alive when TriggerService has zero eligible rules.
        SystemHookSettings.setAuxiliaryFamily(
            context,
            SystemHookFamily.CLIPBOARD,
            true,
        )
        val settings = ClipboardSettings(context)
        store(context).trim(
            retentionDays = settings.retentionDays,
            maxEntries = settings.maxEntries,
        )
    }

    private fun uninstall(context: Context) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        if (listenerRegistered) {
            runCatching { manager?.removePrimaryClipChangedListener(listener) }
            listenerRegistered = false
        }
        SystemHookSettings.setAuxiliaryFamily(
            context,
            SystemHookFamily.CLIPBOARD,
            false,
        )
    }

    fun recordFromSystem(
        context: Context,
        payload: ClipboardPayload,
        onDone: (Boolean) -> Unit = {},
    ) {
        val app = context.applicationContext
        appContext.set(app)
        if (!ClipboardSettings.isEnabled(app)) {
            onDone(false)
            return
        }
        scope.launch {
            val recorded = recordForTest(payload, store(app))
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                onDone(recorded)
            }
        }
    }

    internal fun recordForTest(payload: ClipboardPayload, store: ClipboardHistoryStore): Boolean {
        val now = System.currentTimeMillis()
        val suppress = suppressed.get()
        if (suppress != null && suppress.first == payload.content && now - suppress.second < 1_000L) {
            return false
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(payload.atEpochMs))
        val entry = ClipboardEntry(
            changedAt = payload.atEpochMs,
            changedAtText = time,
            packageName = payload.packageName,
            userId = payload.userId,
            mimeType = payload.mimeType,
            label = payload.label,
            content = payload.content,
            contentLength = payload.originalLength,
            source = payload.source,
            truncated = payload.truncated,
        )
        val id = store.record(entry) ?: return false
        val settings = appContext.get()?.let(::ClipboardSettings)
        if (settings != null) {
            store.trim(
                retentionDays = settings.retentionDays,
                maxEntries = settings.maxEntries,
            )
        }
        return id > 0
    }

    fun suppressNext(text: String) {
        suppressed.set(text to System.currentTimeMillis())
        scope.launch {
            delay(1_200L)
            val current = suppressed.get()
            if (current?.first == text) suppressed.set(null)
        }
    }

    internal fun suppressNextForTest(text: String) = suppressNext(text)

    private fun store(context: Context): ClipboardHistoryStore =
        storeRef.get() ?: ClipboardHistoryStore(context.applicationContext).also { storeRef.set(it) }
}
