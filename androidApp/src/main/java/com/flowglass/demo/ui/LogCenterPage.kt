package com.flowglass.demo.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.demo.system.log.AppLogStore
import com.flowglass.demo.system.log.LogFilter
import com.flowglass.demo.system.log.LogSettings
import com.flowglass.engine.log.TraceEvent
import com.flowglass.engine.log.TraceJson
import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceLevel
import com.flowglass.engine.log.TraceStatus
import com.kyant.shapes.Capsule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PAGE_SIZE = 100

/** Full structured log view; reached from Settings -> 日志记录 and the overview card. */
@Composable
internal fun LogCenterPage(
    store: AppLogStore,
    state: AppState,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = remember { LogSettings(context) }
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(LogFilter(limit = PAGE_SIZE, minLevel = settings.level)) }
    var search by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<TraceEvent>()) }
    var total by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var raw by remember { mutableStateOf(settings.includeRawEvents) }
    var detailTraceId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filter, refresh) {
        val query = filter.copy(offset = 0)
        val result = withContext(Dispatchers.IO) { store.query(query) }
        val count = withContext(Dispatchers.IO) { store.count(query) }
        rows = result
        total = count
    }

    // Avoid querying SQLite on every keystroke; the field stays responsive while debounce runs.
    LaunchedEffect(search) {
        delay(250)
        if (filter.search != search) {
            filter = filter.copy(search = search, offset = 0)
        }
    }

    val traceId = detailTraceId
    if (traceId != null) {
        LogTraceDetailPage(store, traceId) { detailTraceId = null }
        return
    }

    var k = 0
    StatusCard(
        k++,
        Ic.Timer,
        "日志记录",
        if (settings.enabled) "记录中" else "已关闭",
        settings.enabled,
        "$total",
        "条匹配日志",
        listOf(
            "保留 ${settings.retentionDays} 天",
            "级别 ${settings.level.name}",
            if (rows.size < total) "已加载 ${rows.size}" else "全部已加载",
        ),
    )

    GroupTitle("筛选", k++)
    GroupCard(k++) {
        LogSearchField(search) { search = it }
        ListRow(
            Row1(
                Ic.Search,
                "级别",
                settings.level.name,
                sub = "点按循环：错误 → 警告 → 信息 → 调试",
                onClick = {
                    val old = settings.level
                    val next = when (old) {
                        TraceLevel.ERROR -> TraceLevel.WARN
                        TraceLevel.WARN -> TraceLevel.INFO
                        TraceLevel.INFO -> TraceLevel.DEBUG
                        else -> TraceLevel.ERROR
                    }
                    // Record before lowering the accepted level, otherwise the CONFIG trace
                    // itself can be filtered out by the new threshold.
                    store.recordConfig("日志级别", old.name, next.name)
                    settings.level = next
                    filter = filter.copy(minLevel = next, offset = 0)
                    refresh++
                },
            ),
            true,
        )
        ListRow(
            Row1(
                Ic.Info,
                "只看触发器",
                sub = "仅显示事件、匹配、条件",
                onClick = {
                    filter = if (filter.kinds.isEmpty()) {
                        filter.copy(
                            kinds = setOf(TraceKind.EVENT, TraceKind.MATCH, TraceKind.CONDITION),
                            offset = 0,
                        )
                    } else {
                        filter.copy(kinds = emptySet(), offset = 0)
                    }
                    refresh++
                },
            ),
            false,
        )
    }

    GroupTitle("记录策略", k++)
    GroupCard(k++) { bd ->
        ListRow(
            Row1(Ic.Timer, "启用日志记录", sub = "关闭后只保留已有日志"),
            true,
        ) {
            LiquidToggle({ settings.enabled }, { next ->
                val old = settings.enabled
                // Disabling is recorded before the sink starts rejecting every new event.
                if (old && !next) store.recordConfig("启用日志记录", old, next)
                settings.enabled = next
                if (!old && next) store.recordConfig("启用日志记录", old, next)
                refresh++
            }, bd)
        }
        ListRow(
            Row1(
                Ic.Timer,
                "保留天数",
                "${settings.retentionDays} 天",
                sub = "到期后自动清理；点按切换 7 / 14 / 30 天",
                onClick = {
                    val old = settings.retentionDays
                    val next = when (old) {
                        7 -> 14
                        14 -> 30
                        else -> 7
                    }
                    settings.retentionDays = next
                    store.recordConfig("保留天数", old, next)
                    refresh++
                },
            ),
            true,
        )
        ListRow(
            Row1(Ic.Message, "记录事件正文",
                sub = "通知、短信、HTTP 等原文；token/密码仍会脱敏"),
            false,
        ) {
            LiquidToggle({ raw }, { next ->
                val old = settings.includeRawEvents
                raw = next
                settings.includeRawEvents = next
                store.recordConfig("记录事件正文", old, next)
            }, bd)
        }
        ListRow(
            Row1(
                Ic.Share, "导出当前结果", sub = "通过系统分享发送脱敏后的 JSON",
                onClick = {
                    val json = "[${rows.joinToString(",") {
                        TraceJson.format.encodeToString(TraceEvent.serializer(), it)
                    }}]"
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, json)
                    }
                    context.startActivity(Intent.createChooser(send, "导出日志"))
                },
            ),
            true,
        )
        ListRow(
            Row1(
                Ic.Trash, "清空日志", sub = "只清空全局日志，不影响执行记录",
                onClick = {
                    store.clear()
                    refresh++
                },
            ),
            false,
        )
    }

    GroupTitle("日志结果", k++)
    GroupCard(k++) {
        if (rows.isEmpty()) {
            EmptyState(
                Ic.Info,
                "暂无日志",
                if (search.isNotBlank()) "没有匹配「$search」的日志。" else "触发器或手动运行后，这里会出现完整链路。",
            )
        } else {
            rows.forEachIndexed { index, event ->
                LogRow(
                    event,
                    expanded = false,
                    onClick = { detailTraceId = event.traceId },
                )
                if (index < rows.lastIndex) LogDivider()
            }
        }
    }
    if (rows.size < total) {
        Spacer(Modifier.height(10.dp))
        EditorPill(
            if (loadingMore) "正在加载…" else "加载更多（已显示 ${rows.size} / $total）",
            "加载更多日志",
        ) {
            if (loadingMore) return@EditorPill
            val pageFilter = filter.copy(offset = rows.size)
            loadingMore = true
            scope.launch {
                val more = withContext(Dispatchers.IO) { store.query(pageFilter) }
                if (pageFilter.search == filter.search && pageFilter.kinds == filter.kinds) {
                    rows = rows + more
                }
                loadingMore = false
            }
        }
    }
    if (onBack != null) {
        Spacer(Modifier.height(10.dp))
        PrimaryButton("返回", Ic.Back, onBack)
    }
}

@Composable
private fun LogSearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
            .heightIn(min = 46.dp).clip(Capsule()).background(C.Field).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(Ic.Search, C.Muted, 18.dp)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (value.isEmpty()) T("搜索消息、参数、规则或 trace", 14.sp, color = C.Muted)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = C.OnSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(C.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().testTag("log-search"),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.width(30.dp).height(30.dp).clip(Capsule()).background(C.Tonal)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) { T("✕", 12.sp, FontWeight.Bold, C.OnSurfaceVar) }
        }
    }
}

@Composable
private fun LogTraceDetailPage(store: AppLogStore, traceId: String, onBack: () -> Unit) {
    var events by remember(traceId) { mutableStateOf(emptyList<TraceEvent>()) }
    var loaded by remember(traceId) { mutableStateOf(false) }

    LaunchedEffect(traceId) {
        events = withContext(Dispatchers.IO) {
            store.query(LogFilter(traceId = traceId, minLevel = TraceLevel.TRACE, limit = 500))
                .sortedBy { it.atEpochMs }
        }
        loaded = true
    }

    var k = 0
    val first = events.firstOrNull()
    StatusCard(
        k++,
        Ic.Timer,
        "链路详情",
        "trace ${traceId.take(8)}",
        true,
        "${events.size}",
        "个事件",
        listOf(
            first?.ruleName ?: first?.source ?: "系统",
            first?.kind?.name ?: "等待记录",
        ),
    )

    GroupTitle("时间线", k++)
    GroupCard(k++) {
        when {
            !loaded -> Box(Modifier.fillMaxWidth().padding(18.dp)) { T("正在读取…", 13.sp, color = C.Muted) }
            events.isEmpty() -> EmptyState(Ic.Info, "没有找到该链路", "日志可能已被清理。")
            else -> events.forEachIndexed { index, event ->
                LogRow(event, expanded = true, onClick = null)
                if (index < events.lastIndex) LogDivider()
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    PrimaryButton("返回日志列表", Ic.Back, onBack)
}

@Composable
private fun LogRow(event: TraceEvent, expanded: Boolean, onClick: (() -> Unit)?) {
    val time = remember(event.atEpochMs) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.atEpochMs))
    }
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(event.kind.name, 12.sp, FontWeight.Bold, levelColor(event.level))
            Spacer(Modifier.width(8.dp))
            T(time, 12.sp, color = C.Muted)
            Spacer(Modifier.width(8.dp))
            T(event.ruleName ?: event.componentTypeId ?: "系统", 13.sp, FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (onClick != null) T("›", 18.sp, color = C.Muted)
        }
        Spacer(Modifier.height(3.dp))
        T(
            event.message.ifBlank { event.detail }.ifBlank { event.status.name },
            13.sp,
            color = if (event.status == TraceStatus.FAILURE) C.Bad else C.OnSurfaceVar,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
        )
        if (event.detail.isNotBlank() && event.detail != event.message) {
            T(event.detail, 12.sp, color = C.Muted, maxLines = if (expanded) Int.MAX_VALUE else 2)
        }
        T(
            "${event.traceId.take(8)} · ${event.status.name}${event.durationMs?.let { " · ${it}ms" }.orEmpty()}",
            11.sp,
            color = C.Muted,
        )
        if (expanded) {
            LogMap("事件", event.eventData)
            LogMap("参数", event.params)
            LogMap("输出", event.outputs)
            if (!event.errorCode.isNullOrBlank()) T("错误：${event.errorCode}", 12.sp, color = C.Bad)
            val stack = event.errorStack
            if (!stack.isNullOrBlank()) {
                T(stack, 11.sp, color = C.Muted, maxLines = 12)
            }
        }
    }
}

@Composable
private fun LogMap(label: String, values: Map<String, String>) {
    if (values.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    T("$label：${values.entries.joinToString(" · ") { "${it.key}=${it.value}" }}", 11.sp, color = C.Muted)
}

private fun levelColor(level: TraceLevel): Color = when (level) {
    TraceLevel.ERROR -> C.Bad
    TraceLevel.WARN -> C.WarnText
    TraceLevel.INFO -> C.GoodText
    TraceLevel.DEBUG, TraceLevel.TRACE -> C.Muted
}

@Composable
private fun LogDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp).height(1.dp)
            .background(C.Divider),
    )
}
