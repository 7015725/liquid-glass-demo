package com.flowglass.demo.ui

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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections
import com.flowglass.engine.LegacyCommandMigration
import com.flowglass.engine.LegacyFileMigration
import com.flowglass.engine.RuleJson
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleUiMetadata

private val storageQueue = StorageQueue()
private val storageCallbacks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** No seeded state can reach the writer before the initial read finishes. */
@Composable
fun rememberStoredAppState(): AppState? {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { CommandStore(File(context.filesDir, "commands.json")) }
    var state by remember(store) { mutableStateOf<AppState?>(null) }
    LaunchedEffect(store) { state = storageQueue.submit { store.load() }.await().appState() }
    val current = state ?: return null
    current.bindExecutionContext(context)
    val session = remember(current, store) { StoredStateSession(current, store, storageQueue) }
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

internal class StoredStateSession(
    private val state: AppState, private val store: CommandStore, private val queue: StorageQueue,
) {
    private var closed = false
    fun flush() {
        if (closed || !store.canSave) return
        // Capture on the UI thread; neither mutable state nor a stale debounce value crosses into IO.
        val snapshot = state.snapshot()
        queue.submit {
            val error = try { store.save(snapshot); null }
            catch (invalid: IllegalArgumentException) { "参数无效：${invalid.message.orEmpty()}；原存档已保留，请修正后保存。" }
            catch (_: Exception) { "本地保存失败，请检查可用空间；修改尚未保存。" }
            storageCallbacks.launch { state.storageError = error }
        }
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
    val engineUi: RuleUiMetadata = RuleUiMetadata(running = running, mode = mode),
) {
    val autoCmds = immutableCommands(autoCmds)
    val oneTaps = immutableCommands(oneTaps)
    val logs: List<RunLog> = immutableList(logs.map { it.copy(steps = immutableList(it.steps)) })
    val engineRules: List<Rule> = immutableList(engineRules)

    override fun equals(other: Any?): Boolean = other is PersistedAppState &&
        autoCmds == other.autoCmds && oneTaps == other.oneTaps && logs == other.logs &&
        running == other.running && mode == other.mode && engineRules == other.engineRules
    override fun hashCode(): Int = listOf(autoCmds, oneTaps, logs, running, mode, engineRules).hashCode()
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun immutableCommands(commands: List<Cmd>) = immutableList(commands.map {
    it.copy(triggers = immutableList(it.triggers), conditions = immutableList(it.conditions), actions = immutableList(it.actions))
})

internal fun AppState.snapshot() = PersistedAppState(autoCmds, oneTaps, logs, running, mode, engineRules, engineUi)

internal object AppStateJson {
    data class Decoded(val state: PersistedAppState, val migrated: Boolean)

    fun encode(state: PersistedAppState): String = RuleJson.encode(state.toRuleDocument())

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
                Decoded(LegacyCommandMigration.migrate(PreviewV2Migration.toLegacyJson(root)).toPersistedAppState(), migrated = true)
            } else Decoded(RuleJson.decode(text).toPersistedAppState(), migrated = false)
        } else {
            val document = LegacyCommandMigration.migrate(text)
            Decoded(document.toPersistedAppState(), migrated = true)
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
                '{', '[' -> { depth++; require(depth <= 16) { "存储嵌套过深" } }
                '}', ']' -> depth--
            }
        }
    }

}
