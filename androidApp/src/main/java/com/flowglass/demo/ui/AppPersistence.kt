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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections

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
) {
    val autoCmds = immutableCommands(autoCmds)
    val oneTaps = immutableCommands(oneTaps)
    val logs: List<RunLog> = immutableList(logs.map { it.copy(steps = immutableList(it.steps)) })

    override fun equals(other: Any?): Boolean = other is PersistedAppState &&
        autoCmds == other.autoCmds && oneTaps == other.oneTaps && logs == other.logs &&
        running == other.running && mode == other.mode
    override fun hashCode(): Int = listOf(autoCmds, oneTaps, logs, running, mode).hashCode()
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun immutableCommands(commands: List<Cmd>) = immutableList(commands.map {
    it.copy(triggers = immutableList(it.triggers), conditions = immutableList(it.conditions), actions = immutableList(it.actions))
})

internal fun AppState.snapshot() = PersistedAppState(autoCmds, oneTaps, logs, running, mode)

internal object AppStateJson {
    fun encode(state: PersistedAppState): String = JSONObject().apply {
        put("version", 1)
        put("autoCmds", array(state.autoCmds, ::commandJson))
        put("oneTaps", array(state.oneTaps, ::commandJson))
        put("logs", array(state.logs) { log -> JSONObject().apply {
            put("id", log.id); put("title", log.title); put("reason", log.reason)
            put("time", log.time); put("ok", log.ok); put("ic", log.ic.name)
            put("detail", log.detail)
            put("steps", array(log.steps) { step -> JSONObject().apply {
                put("itemId", step.itemId); put("name", step.name); put("status", step.status.name)
                put("detail", step.detail); put("durationMs", step.durationMs)
            } })
        } })
        put("running", state.running)
        put("mode", state.mode)
    }.toString()

    fun decode(text: String): PersistedAppState {
        checkNesting(text)
        val reader = JSONTokener(text)
        val root = reader.nextValue() as? JSONObject ?: error("存储内容不是对象")
        require(reader.nextClean() == '\u0000') { "存储内容含多余数据" }
        require(root.long("version") == 1L) { "不支持的存储版本" }
        val auto = root.objects("autoCmds", ::command)
        val manual = root.objects("oneTaps", ::command)
        require(auto.all { it.auto } && manual.all { !it.auto && it.triggers.isEmpty() && it.conditions.isEmpty() })
        val logs = root.objects("logs") {
            val steps = if (it.has("steps")) it.objects("steps") { step ->
                val duration = step.long("durationMs")
                require(duration >= 0) { "任务耗时无效" }
                com.flowglass.demo.system.exec.StepResult(step.long("itemId"), step.string("name"),
                    com.flowglass.demo.system.exec.StepStatus.valueOf(step.string("status")), step.string("detail"), duration)
            } else emptyList()
            RunLog(it.long("id"), it.string("title"), it.string("reason"), it.string("time"), it.bool("ok"), it.icon(),
                if (it.has("detail")) it.string("detail") else "", steps)
        }
        val mode = root.long("mode")
        require(mode in 0L..2L) { "运行模式无效" }
        val ids = (auto + manual).flatMap { cmd ->
            listOf(cmd.id) + (cmd.triggers + cmd.conditions + cmd.actions).map { it.id }
        } + logs.map { it.id }
        require(ids.all { it < Long.MAX_VALUE } && ids.distinct().size == ids.size) { "存储标识无效或重复" }
        return PersistedAppState(auto, manual, logs, root.bool("running"), mode.toInt())
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

    private fun commandJson(c: Cmd) = JSONObject().apply {
        put("id", c.id); put("auto", c.auto); put("ic", c.ic.name)
        put("title", c.title); put("desc", c.desc); put("descriptionEdited", c.descriptionEdited)
        put("triggers", array(c.triggers, ::itemJson)); put("conditions", array(c.conditions, ::itemJson))
        put("actions", array(c.actions, ::itemJson)); put("category", c.category)
        put("enabled", c.enabled); put("pinned", c.pinned); put("lastRun", c.lastRun)
    }

    private fun itemJson(item: CommandItem) = JSONObject().apply {
        put("id", item.id); put("name", item.name); put("parameter", item.parameter); put("ic", item.ic.name)
    }

    private fun command(o: JSONObject): Cmd {
        val category = o.long("category")
        require(category in AutoCategories.indices.map { it.toLong() }) { "指令分类无效" }
        return Cmd(o.long("id"), o.bool("auto"), o.icon(), o.string("title"), o.string("desc"),
            o.objects("triggers", ::item), o.objects("conditions", ::item), o.objects("actions", ::item),
            category.toInt(), o.bool("enabled"), o.bool("pinned"), o.string("lastRun"), o.bool("descriptionEdited"))
    }

    private fun item(o: JSONObject) = CommandItem(o.long("id"), o.string("name"), o.string("parameter"), o.icon())
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("$key 不是文本")
    private fun JSONObject.bool(key: String) = get(key) as? Boolean ?: error("$key 不是布尔值")
    private fun JSONObject.long(key: String): Long = when (val value = get(key)) {
        is Long -> value
        is Int -> value.toLong()
        else -> error("$key 不是整数")
    }
    private fun JSONObject.icon() = Ic.valueOf(string("ic"))
    private fun <T> JSONObject.objects(key: String, decode: (JSONObject) -> T): List<T> {
        val values = getJSONArray(key)
        return List(values.length()) { decode(values.getJSONObject(it)) }
    }
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray().apply {
        values.forEach { put(encode(it)) }
    }
}
