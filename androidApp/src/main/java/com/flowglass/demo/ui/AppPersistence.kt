package com.flowglass.demo.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.flowglass.engine.OutputCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections
import com.flowglass.engine.LegacyCommandMigration
import com.flowglass.engine.LegacyFileMigration
import com.flowglass.engine.RuleJson
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleUiMetadata

private val storageCallbacks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * Process-wide owner of the parsed state and its single writer.
 *
 * The Activity and the trigger foreground service are in the same process. Sharing one
 * [AppState] prevents the service from persisting a stale copy over an editor change while the
 * UI is open, and lets a headless trigger run append its execution record immediately.
 */
internal class AppRuntime private constructor(private val context: Context) {
    private val store = CommandStore(File(context.filesDir, "commands.json"))
    /** Process-wide structured log store; SQLite writes are queued off the caller thread. */
    val logStore = com.flowglass.demo.system.log.AppLogStore(context)
    private val queue = StorageQueue()
    private val lock = Any()
    private val parsed = StoreLoadCache(store::load)
    @Volatile private var appState: AppState? = null

    /** Test seam: true only when the snapshot state was built on the Android main thread. */
    @Volatile internal var stateCreatedOnMainThread = false

    /** Number of physical store reads; callers racing on [load] must share one result. */
    internal val storeLoadCount: Int get() = parsed.loadCount

    val state: AppState? get() = appState

    /**
     * Safe to call from any thread. Callers may arrive concurrently from the Activity and the
     * trigger service: parsing is serialized and cached so migration writes happen only once.
     * [StoreLoadCache.get] releases its lock before [onMainThread] waits for the main thread;
     * holding it while waiting would deadlock when main itself calls [load].
     *
     * [AppState] contains Compose snapshot state and must be created on the main thread: a
     * state created in a worker snapshot is invisible to the initial composition snapshot and
     * throws "Reading a state that was created after the snapshot was taken".
     */
    fun load(): AppState {
        appState?.let { return it }
        val loaded = parsed.get()
        return onMainThread {
            synchronized(lock) {
                appState ?: loaded.appState().also { created ->
                    created.bindExecutionContext(context, logStore)
                    stateCreatedOnMainThread = true
                    appState = created
                }
            }
        }
    }

    /** Test seam: whether ordinary saving is currently allowed. */
    internal fun canSaveForTest(): Boolean = store.canSave

    /** Test seam: waits until every queued write finished. */
    internal suspend fun drainForTest() {
        queue.submit { }.await()
    }

    /** Test-only reset; production ownership is the process-wide singleton. */
    internal fun resetForTest() {
        synchronized(lock) {
            appState = null
            stateCreatedOnMainThread = false
        }
        parsed.resetForTest()
    }

    private fun <T> onMainThread(block: () -> T): T {
        val main = Looper.getMainLooper() ?: return block()
        if (Looper.myLooper() == main) return block()
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val posted = Handler(main).post {
            try {
                value.set(block())
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                done.countDown()
            }
        }
        if (!posted) return block()
        done.await()
        failure.get()?.let { throw it }
        return checkNotNull(value.get()) { "主线程未返回 AppState" }
    }

    fun requestSave(capture: () -> PersistedAppState) {
        if (!store.canSave) return
        // Captured on the caller thread: a restore that starts before this save lands bumps the
        // epoch, and the stale snapshot is dropped instead of overwriting the restored document.
        val epoch = store.currentEpoch()
        val snapshot = capture()
        queue.submit {
            val error = try {
                if (!store.save(snapshot, epoch)) return@submit
                com.flowglass.demo.xposed.method.MethodHookConfigPublisher.publish(context, snapshot)
                null
            } catch (invalid: IllegalArgumentException) {
                "参数无效：${invalid.message.orEmpty()}；原存档已保留，请修正后保存。"
            } catch (_: Exception) {
                "本地保存失败，请检查可用空间；修改尚未保存。"
            }
            if (error != null) storageCallbacks.launch { appState?.storageError = error }
        }
    }

    /**
     * Explicit restore transaction. The durable write happens while ordinary saving is still
     * blocked, so a lifecycle flush or trigger save during the window is dropped instead of being
     * queued behind the repair; [publish] then makes the document live and only afterwards may
     * saving resume. Returns the preserved original bytes, if any.
     */
    suspend fun commitRestore(candidate: PersistedAppState, publish: () -> Unit): File? {
        // Synchronous on the caller: ordinary saving is closed before the write is queued, even
        // when the store was healthy and writable.
        val couldSave = store.beginRepair()
        var preserved: File? = null
        var repaired = false
        try {
            preserved = queue.submit { store.repair(candidate) }.await()
            repaired = true
            publish()
            queue.submit {
                store.completeRepair()
                // Hook configuration is derived data; a failure here must not mask a completed repair.
                runCatching {
                    com.flowglass.demo.xposed.method.MethodHookConfigPublisher.publish(context, candidate)
                }
            }.await()
            return preserved
        } catch (failure: Throwable) {
            if (repaired) {
                // The candidate may already be on disk although it never became live.
                val kept = preserved
                if (kept != null) runCatching { queue.submit { store.rollbackTo(kept) }.await() }
            }
            store.abortRepair(couldSave)
            throw failure
        }
    }

    fun close() {
        queue.close()
    }

    companion object {
        @Volatile private var instance: AppRuntime? = null

        fun of(context: Context): AppRuntime {
            val app = context.applicationContext
            instance?.let { if (it.context === app) return it }
            return synchronized(this) {
                instance?.let { if (it.context === app) return it }
                AppRuntime(app).also { instance = it }
            }
        }
    }
}

/** No seeded state can reach the writer before the initial read finishes. */
@Composable
fun rememberStoredAppState(): AppState? {
    val context = LocalContext.current.applicationContext
    val runtime = remember(context) { AppRuntime.of(context) }
    var state by remember(runtime) { mutableStateOf(runtime.state) }
    LaunchedEffect(runtime) {
        if (state == null) state = withContext(Dispatchers.IO) { runtime.load() }
    }
    val current = state ?: return null
    val session = remember(current, runtime) { StoredStateSession(current, runtime) }
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    DisposableEffect(session) {
        onDispose { session.close() }
    }
    DisposableEffect(session, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.flush()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(session) { observeStoredState(current) { session.flush() } }
    return current
}

/** FIFO across reads and writes, including a final flush followed by recreation. */
internal class StorageQueue(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val requests = Channel<() -> Unit>(Channel.UNLIMITED)
    init {
        CoroutineScope(dispatcher).launch { for (request in requests) request() }
    }
    fun <T> submit(block: () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        val accepted = requests.trySend {
            try { result.complete(block()) }
            catch (failure: Exception) { result.completeExceptionally(failure) }
        }
        if (accepted.isFailure) result.completeExceptionally(IllegalStateException("保存队列已关闭"))
        return result
    }
    fun close() { requests.close() }
}

/**
 * Serializes store reads and caches the parsed value. [load] may perform a migration and
 * write `commands.json` / its backup, so two cold-start callers must never run it in parallel.
 */
internal class StoreLoadCache(private val load: () -> StoreLoad) {
    private val lock = Any()
    private var cached: StoreLoad? = null
    var loadCount: Int = 0
        private set

    fun get(): StoreLoad = synchronized(lock) {
        cached ?: load().also {
            cached = it
            loadCount += 1
        }
    }

    fun resetForTest() = synchronized(lock) {
        cached = null
        loadCount = 0
    }
}

internal class StoredStateSession(
    private val state: AppState, private val runtime: AppRuntime,
) {
    private var closed = false
    fun flush() {
        if (closed) return
        // Capture on the UI thread; neither mutable state nor a stale debounce value crosses into IO.
        runtime.requestSave { state.snapshot() }
    }
    fun close() {
        flush()
        closed = true
        // The process queue intentionally outlives this composition and drains its final write.
    }
}

@OptIn(FlowPreview::class)
internal suspend fun observeStoredState(state: AppState, save: () -> Unit) {
    // Do not collectLatest: AtomicFile transactions must not be cancelled midway.
    snapshotFlow { state.snapshot() }.debounce(500).collect { save() }
}

/** Detached, deeply read-only value used by snapshotFlow and the IO writer. */
internal class PersistedAppState(
    autoCmds: List<Cmd>, oneTaps: List<Cmd>, logs: List<RunLog>,
    val running: Boolean, val mode: Int,
    engineRules: List<Rule> = emptyList(),
    engineUi: RuleUiMetadata = RuleUiMetadata(running = running, mode = mode),
    engineGlobals: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    engineGlobalMeta: Map<String, com.flowglass.engine.GlobalVariableMeta> = emptyMap(),
) {
    val autoCmds = immutableCommands(autoCmds)
    val oneTaps = immutableCommands(oneTaps)
    val logs: List<RunLog> = immutableList(logs.map { it.copy(steps = immutableList(it.steps)) })
    val engineRules: List<Rule> = immutableList(engineRules)
    val engineUi: RuleUiMetadata = engineUi
    val engineGlobals: Map<String, kotlinx.serialization.json.JsonElement> =
        Collections.unmodifiableMap(LinkedHashMap(engineGlobals))
    val engineGlobalMeta: Map<String, com.flowglass.engine.GlobalVariableMeta> =
        Collections.unmodifiableMap(LinkedHashMap(engineGlobalMeta))

    override fun equals(other: Any?): Boolean = other is PersistedAppState &&
        autoCmds == other.autoCmds && oneTaps == other.oneTaps && logs == other.logs &&
        running == other.running && mode == other.mode && engineRules == other.engineRules &&
        engineGlobals == other.engineGlobals && engineGlobalMeta == other.engineGlobalMeta
    override fun hashCode(): Int =
        listOf(autoCmds, oneTaps, logs, running, mode, engineRules, engineGlobals, engineGlobalMeta).hashCode()
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun immutableCommands(commands: List<Cmd>) = immutableList(commands.map {
    it.copy(
        triggers = immutableList(it.triggers.map(::immutableItem)),
        conditions = immutableList(it.conditions.map(::immutableItem)),
        actions = immutableList(it.actions.map(::immutableItem)),
    )
})

private fun immutableItem(item: CommandItem): CommandItem = item.copy(
    children = immutableList(item.children.map(::immutableItem)),
    conditions = immutableList(item.conditions.map(::immutableItem)),
    branches = immutableList(item.branches.map { branch ->
        branch.copy(
            children = immutableList(branch.children.map(::immutableItem)),
            conditions = immutableList(branch.conditions.map(::immutableItem)),
        )
    }),
)

internal fun AppState.snapshot() = PersistedAppState(
    autoCmds, oneTaps, logs, running, mode, engineRules,
    engineUi.copy(startedAt = engineStartedAt),
    engineGlobals,
    engineGlobalMeta,
)

internal object AppStateJson {
    /** [backupName] names the pre-migration copy; it is per source format so upgrades never collide. */
    data class Decoded(
        val state: PersistedAppState,
        val migrated: Boolean,
        val backupName: String? = null,
    )

    fun encode(state: PersistedAppState): String {
        val document = state.toRuleDocument()
        // A missing call target, a cycle or an over-deep chain is user-repairable. Refusing the
        // whole snapshot here froze every unrelated edit, global variable and log entry. Loading is
        // tolerant, the editor reports the link, and execution refuses to run past MAX_DEPTH, so
        // the document is written as-is and repaired in place.
        return RuleJson.encode(document)
    }

    fun decode(text: String): PersistedAppState {
        checkNesting(text)
        return decodeDocument(text).state
    }

    fun decodeDocument(text: String): Decoded {
        checkNesting(text)
        val reader = JSONTokener(text)
        val root = reader.nextValue() as? JSONObject ?: error("存储内容不是对象")
        require(reader.nextClean() == '\u0000') { "存储内容含多余数据" }
        return if (root.has("formatVersion")) {
            if (PreviewV2Migration.looksLikePreview(root)) {
                // Pre-release 0.6.0-alpha1 bytes: same formatVersion, old presentation sidecar.
                Decoded(
                    OutputCatalog.normalizeDocument(
                        LegacyCommandMigration.migrate(PreviewV2Migration.toLegacyJson(root)),
                    ).toPersistedAppState(),
                    migrated = true,
                    backupName = "commands.preview-v2.bak.json",
                )
            } else {
                val decoded = RuleJson.decodeWithMigration(text)
                Decoded(
                    OutputCatalog.normalizeDocument(decoded.document).toPersistedAppState(),
                    migrated = decoded.migratedFrom != null,
                    backupName = when (decoded.migratedFrom) {
                        2 -> "commands.v2.bak.json"
                        3 -> "commands.v3.bak.json"
                        4 -> "commands.v4.bak.json"
                        5 -> "commands.v5.bak.json"
                        else -> null
                    },
                )
            }
        } else {
            val document = OutputCatalog.normalizeDocument(LegacyCommandMigration.migrate(text))
            Decoded(document.toPersistedAppState(), migrated = true, backupName = "commands.v1.bak.json")
        }
    }

    // The on-disk schema is shallow. Guard before org.json's recursive parser,
    // not afterwards; brackets inside arbitrary parameter text are not nesting.
    private fun checkNesting(text: String) {
        var depth = 0
        var quote = '\u0000'
        var escaped = false
        for (char in text) {
            if (quote != '\u0000') {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == quote) quote = '\u0000'
            } else when (char) {
                '"', '\'' -> quote = char
                '{', '[' -> { depth++; require(depth <= 256) { "存储嵌套过深" } }
                '}', ']' -> depth--
            }
        }
    }

}
