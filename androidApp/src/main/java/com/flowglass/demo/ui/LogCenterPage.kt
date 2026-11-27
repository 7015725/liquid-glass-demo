package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.engine.log.TraceJson
import com.flowglass.demo.system.log.AppLogStore
import com.flowglass.demo.system.log.LogFilter
import com.flowglass.demo.system.log.LogSettings
import com.flowglass.engine.log.TraceEvent
import com.flowglass.engine.log.TraceKind
import com.flowglass.engine.log.TraceLevel
import com.flowglass.engine.log.TraceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full structured log view; reached from Settings -> 日志记录 and the overview card. */
@Composable
internal fun LogCenterPage(
    store: AppLogStore,
    state: AppState,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = remember { LogSettings(context) }
    var filter by remember { mutableStateOf(LogFilter(limit = 200)) }
    var rows by remember { mutableStateOf(emptyList<TraceEvent>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var raw by remember { mutableStateOf(settings.includeRawEvents) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filter, refresh) {
        rows = withContext(Dispatchers.IO) { store.query(filter) }
    }

    var k = 0
    StatusCard(
        k++,
        Ic.Timer,
        "日志记录",
        if (settings.enabled) "记录中" else "已关闭",
        settings.enabled,
        "${rows.size}",
        "条当前结果",
        listOf("保留 ${settings.retentionDays} 天", "级别 ${settings.level.name}", "执行记录 ${state.logs.size} 条"),
    )

    GroupTitle("筛选", k++)
    GroupCard(k++) {
        ListRow(
            Row1(
                Ic.Search,
                "级别",
                settings.level.name,
                sub = "点按循环：错误 → 警告 → 信息 → 调试",
                onClick = {
                    settings.level = when (settings.level) {
                        TraceLevel.ERROR -> TraceLevel.WARN
                        TraceLevel.WARN -> TraceLevel.INFO
                        TraceLevel.INFO -> TraceLevel.DEBUG
                        else -> TraceLevel.ERROR
                    }
                    filter = filter.copy(minLevel = settings.level)
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
                        filter.copy(kinds = setOf(TraceKind.EVENT, TraceKind.MATCH, TraceKind.CONDITION))
                    } else {
                        filter.copy(kinds = emptySet())
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
            LiquidToggle({ settings.enabled }, {
                settings.enabled = it
                refresh++
            }, bd)
        }
        ListRow(
            Row1(Ic.Timer, "保留天数", "${settings.retentionDays} 天",
                sub = "到期后自动清理；点按切换 7 / 14 / 30 天"),
            true,
        ) {
            settings.retentionDays = when (settings.retentionDays) {
                7 -> 14
                14 -> 30
                else -> 7
            }
            refresh++
        }
        ListRow(
            Row1(Ic.Message, "记录事件正文",
                sub = "通知、短信、HTTP 等原文；token/密码仍会脱敏"),
            false,
        ) {
            LiquidToggle({ raw }, {
                raw = it
                settings.includeRawEvents = it
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

    GroupTitle("最近日志", k++)
    GroupCard(k++) {
        if (rows.isEmpty()) {
            EmptyState(Ic.Info, "暂无日志", "触发器或手动运行后，这里会出现完整链路。")
        } else {
            rows.take(200).forEachIndexed { index, event ->
                LogRow(
                    event,
                    expanded = expandedId == event.id,
                    onClick = {
                        expandedId = if (expandedId == event.id) null else event.id
                    },
                )
                if (index < rows.lastIndex) LogDivider()
            }
        }
    }
    if (onBack != null) {
        PrimaryButton("返回", Ic.Back, onBack)
    }
}

@Composable
private fun LogRow(event: TraceEvent, expanded: Boolean, onClick: () -> Unit) {
    val time = remember(event.atEpochMs) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.atEpochMs))
    }
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(event.kind.name, 12.sp, FontWeight.Bold, levelColor(event.level))
            Spacer(Modifier.width(8.dp))
            T(time, 12.sp, color = C.Muted)
            Spacer(Modifier.width(8.dp))
            T(event.ruleName ?: event.componentTypeId ?: "系统", 13.sp, FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.weight(1f))
            T(if (expanded) "⌃" else "⌄", 14.sp, color = C.Muted)
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
