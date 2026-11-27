package com.flowglass.demo.system.clipboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.ui.C
import com.flowglass.demo.ui.Ic
import com.flowglass.demo.ui.LineIcon
import com.flowglass.demo.ui.Spinner
import com.flowglass.demo.ui.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ClipboardOverlayScreen(
    store: ClipboardHistoryStore,
    refreshTick: Int,
    onClose: () -> Unit,
    onCopy: (ClipboardEntry) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var searchOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<ClipboardPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var copiedId by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val retentionDays = remember { ClipboardSettings(context).retentionDays }

    LaunchedEffect(search, refreshTick, refresh) {
        loading = true
        if (search.isNotBlank()) delay(220)
        page = withContext(Dispatchers.IO) {
            store.query(ClipboardQuery(search = search, limit = 200))
        }
        loading = false
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(80)
            runCatching { searchFocus.requestFocus() }
        } else {
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(copiedId) {
        if (copiedId != null) {
            delay(1_500)
            copiedId = null
        }
    }

    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(2_500)
            confirmClear = false
        }
    }

    BackHandler(enabled = true) {
        if (searchOpen) {
            searchOpen = false
            search = ""
        } else {
            onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f)),
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.24f)
                .clickable(onClick = onClose),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            ClipboardSheet(
                store = store,
                page = page,
                loading = loading,
                search = search,
                searchOpen = searchOpen,
                searchFocus = searchFocus,
                copiedId = copiedId,
                confirmClear = confirmClear,
                retentionDays = retentionDays,
                onSearchToggle = {
                    searchOpen = !searchOpen
                    if (!searchOpen) search = ""
                },
                onSearchChange = { search = it },
                onSearchClose = {
                    searchOpen = false
                    search = ""
                },
                onCopy = { entry ->
                    onCopy(entry)
                    copiedId = entry.id
                },
                onDelete = { entry ->
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(entry.id) }
                        refresh += 1
                    }
                },
                onClear = {
                    if (confirmClear) {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.clear() }
                            confirmClear = false
                            refresh += 1
                        }
                    } else {
                        confirmClear = true
                    }
                },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ClipboardSheet(
    store: ClipboardHistoryStore,
    page: ClipboardPage?,
    loading: Boolean,
    search: String,
    searchOpen: Boolean,
    searchFocus: FocusRequester,
    copiedId: Long?,
    confirmClear: Boolean,
    retentionDays: Int,
    onSearchToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onCopy: (ClipboardEntry) -> Unit,
    onDelete: (ClipboardEntry) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(C.Bg)
            .background(
                Brush.verticalGradient(
                    listOf(
                        C.TonalStrong.copy(alpha = 0.72f),
                        C.Card.copy(alpha = 0.22f),
                        Color.Transparent,
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        C.OnSurface.copy(alpha = 0.20f),
                        C.Divider.copy(alpha = 0.18f),
                    ),
                ),
                shape,
            ),
    ) {
        // A thin specular line makes the panel read as glass rather than a flat card.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 34.dp)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, C.OnSurface.copy(alpha = 0.25f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .padding(top = 9.dp)
                .width(34.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(C.Divider),
        )
        val searchProgress by animateFloatAsState(
            targetValue = if (searchOpen) 1f else 0f,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            label = "clipboardSearch",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(54.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = (1f - searchProgress).coerceIn(0f, 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T("全局剪贴板", 19.sp, FontWeight.Bold, C.OnSurface, maxLines = 1)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(C.PrimarySoft.copy(alpha = 0.62f))
                                    .border(
                                        1.dp,
                                        C.Primary.copy(alpha = 0.22f),
                                        RoundedCornerShape(50),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                T(
                                    if (page == null) "…" else "${page.total} 条",
                                    10.sp,
                                    FontWeight.SemiBold,
                                    C.Primary,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        T(
                            if (page == null) "正在读取本机记录…" else "${page.total} 条记录 · 仅保存在本机",
                            11.sp,
                            color = C.Muted,
                            maxLines = 1,
                        )
                    }
                    OverlayIconButton(
                        icon = Ic.Search,
                        contentDescription = "搜索",
                        onClick = onSearchToggle,
                    )
                }
                if (searchProgress > 0.001f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = searchProgress
                                scaleX = 0.18f + 0.82f * searchProgress
                                transformOrigin = TransformOrigin(1f, 0.5f)
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        ClipboardSearchField(
                            value = search,
                            focusRequester = searchFocus,
                            onValueChange = onSearchChange,
                            onClose = onSearchClose,
                        )
                    }
                }
            }
            Spacer(Modifier.width(2.dp))
            OverlayIconButton(
                icon = Ic.Close,
                contentDescription = "关闭",
                onClick = onClose,
            )
        }

        if (loading && page == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Spinner(C.Primary, 20.dp)
            }
        } else {
            val rows = page?.rows.orEmpty()
            if (rows.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    EmptyClipboard(search.isNotBlank())
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(rows, key = { it.id }) { entry ->
                        ClipboardRow(
                            entry = entry,
                            copied = copiedId == entry.id,
                            onCopy = { onCopy(entry) },
                            onDelete = { onDelete(entry) },
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(
                "共 ${page?.total ?: 0} 条 · 保留 $retentionDays 天",
                11.sp,
                color = C.Muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            T("·", 11.sp, color = C.Divider)
            Spacer(Modifier.width(8.dp))
            T("复制后自动保存", 10.sp, color = C.Muted.copy(alpha = 0.72f), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                T(
                    if (confirmClear) "再点一次清空" else "清空全部",
                    11.sp,
                    FontWeight.Medium,
                    if (confirmClear) C.WarnText else C.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ClipboardSearchField(
    value: String,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(C.Field)
            .border(1.dp, C.Divider.copy(alpha = 0.36f), RoundedCornerShape(50))
            .padding(start = 13.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(Ic.Search, C.Muted, 17.dp)
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = C.OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                ),
                singleLine = true,
                cursorBrush = SolidColor(C.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            if (value.isEmpty()) {
                T("搜索内容或应用", 14.sp, color = C.Muted, maxLines = 1)
            }
        }
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .semanticsDescription("关闭搜索"),
            contentAlignment = Alignment.Center,
        ) {
            LineIcon(Ic.Close, C.Muted, 15.dp)
        }
    }
}

@Composable
private fun ClipboardRow(
    entry: ClipboardEntry,
    copied: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember(entry.packageName) { appLabel(context, entry.packageName) }
    val time = remember(entry.changedAt) { shortTime(entry.changedAt) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(C.Tonal.copy(alpha = 0.72f))
            .border(1.dp, C.Divider.copy(alpha = 0.34f), RoundedCornerShape(17.dp))
            .padding(start = 13.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("#${entry.id}", 10.sp, color = C.Muted, tabular = true, maxLines = 1)
            SourceDot(app)
            T(app, 11.sp, FontWeight.Medium, C.OnSurfaceVar, maxLines = 1, modifier = Modifier.weight(1f))
            T(time, 10.sp, color = C.Muted, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        T(
            entry.content,
            14.sp,
            color = C.OnSurface,
            maxLines = 2,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(
                "${entry.contentLength} 字符",
                10.sp,
                color = C.Muted.copy(alpha = 0.82f),
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(C.OnSurface.copy(alpha = 0.045f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                T(entry.mimeType.substringBefore(';'), 9.sp, color = C.Muted.copy(alpha = 0.72f), maxLines = 1)
            }
            if (entry.truncated) {
                Spacer(Modifier.width(6.dp))
                T("已截断", 9.sp, color = C.WarnText, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (copied) C.GoodSoft.copy(alpha = 0.72f) else C.PrimarySoft.copy(alpha = 0.58f))
                    .border(
                        1.dp,
                        if (copied) C.Good.copy(alpha = 0.28f) else C.Primary.copy(alpha = 0.22f),
                        RoundedCornerShape(9.dp),
                    )
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LineIcon(if (copied) Ic.Check else Ic.Copy, if (copied) C.GoodText else C.Primary, 13.dp)
                Spacer(Modifier.width(4.dp))
                T(
                    if (copied) "已复制" else "复制",
                    10.sp,
                    FontWeight.Medium,
                    if (copied) C.GoodText else C.Primary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                LineIcon(Ic.TrashMin, C.Muted, 14.dp)
            }
        }
    }
}

@Composable
private fun SourceDot(label: String) {
    val initial = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    val colors = when {
        label.contains("chrome", ignoreCase = true) -> listOf(Color(0xFF4C8DFF), Color(0xFF2457C5))
        label.contains("微信") || label.contains("wechat", ignoreCase = true) ->
            listOf(Color(0xFF42D7A0), Color(0xFF12956E))
        label.contains("bilibili", ignoreCase = true) || label.contains("哔哩", ignoreCase = true) ->
            listOf(Color(0xFFFF9AB8), Color(0xFFC93E68))
        else -> listOf(C.TonalStrong, C.Tonal)
    }
    Box(
        Modifier
            .padding(horizontal = 5.dp)
            .size(16.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        T(initial, 8.sp, FontWeight.Bold, Color.White, maxLines = 1)
    }
}

@Composable
private fun EmptyClipboard(searched: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(C.Tonal)
                .border(1.dp, C.Divider, RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
            LineIcon(if (searched) Ic.Search else Ic.Copy, C.Muted, 22.dp)
        }
        Spacer(Modifier.height(12.dp))
        T(
            if (searched) "没有找到匹配记录" else "还没有剪贴板记录",
            14.sp,
            FontWeight.Medium,
            C.OnSurfaceVar,
        )
        Spacer(Modifier.height(4.dp))
        T(
            if (searched) "试试其他关键词或应用名" else "复制一段文字，这里会自动保存",
            11.sp,
            color = C.Muted,
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: Ic,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (active) C.PrimarySoft.copy(alpha = 0.72f) else C.OnSurface.copy(alpha = 0.055f))
            .border(
                1.dp,
                if (active) C.Primary.copy(alpha = 0.30f) else C.Divider.copy(alpha = 0.28f),
                CircleShape,
            )
            .clickable(onClick = onClick)
            .semanticsDescription(contentDescription),
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(icon, if (active) C.Primary else C.OnSurfaceVar, 18.dp)
    }
}

private fun Modifier.semanticsDescription(description: String): Modifier =
    this.semantics { contentDescription = description }

private fun appLabel(context: android.content.Context, packageName: String): String {
    if (packageName.isBlank()) return "未知来源"
    when (packageName) {
        "com.android.chrome" -> return "Chrome"
        "com.microsoft.emmx" -> return "Edge"
        "com.tencent.mm" -> return "微信"
        "com.android.systemui" -> return "系统 UI"
        "tv.danmaku.bili" -> return "哔哩哔哩"
    }
    return runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}

private fun shortTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val sameYear = SimpleDateFormat("yyyy", Locale.getDefault())
        .format(Date(epochMs)) == SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(now))
    return SimpleDateFormat(if (sameYear) "MM-dd HH:mm" else "yyyy-MM-dd", Locale.getDefault())
        .format(Date(epochMs))
}
