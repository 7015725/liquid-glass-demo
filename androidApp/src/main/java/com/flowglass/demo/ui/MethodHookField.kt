package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.xposed.method.MethodHookCatalog
import com.kyant.shapes.RoundedRectangle

/**
 * Trigger parameter for a method hook. It always selects a saved definition by id; the old free
 * text field made a typo silently produce a trigger that could never match.
 */
@Composable
internal fun MethodHookField(
    current: String,
    tag: String,
    onValue: (String) -> Unit,
    onOpenMethodHooks: () -> Unit,
) {
    val context = LocalContext.current
    var hooks by remember { mutableStateOf(MethodHookCatalog.all()) }
    var expanded by rememberSaveable(tag) { mutableStateOf(false) }
    LaunchedEffect(tag) {
        MethodHookCatalog.refresh(context)
        hooks = MethodHookCatalog.all()
    }
    val selected = hooks.firstOrNull { it.id == current }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedRectangle(22.dp))
                .background(C.Card)
                .testTag(tag)
                .editorAction("选择方法钩子") {
                    if (hooks.isEmpty()) onOpenMethodHooks() else expanded = !expanded
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(
                    selected?.let { "${it.packageName}@${it.userId} · ${it.className}#${it.methodName}" }
                        ?: "未选择方法钩子",
                    15.sp,
                    FontWeight.Medium,
                    maxLines = 1,
                )
                T(
                    selected?.let { if (it.enabled) "已启用" else "未启用（保存后在设置中打开）" }
                        ?: "点选已创建的方法钩子",
                    12.sp,
                    color = C.OnSurfaceVar,
                    maxLines = 1,
                )
            }
            T(if (expanded) "收起" else "选择", 13.sp, FontWeight.SemiBold, C.Primary)
        }
        if (hooks.isEmpty()) {
            T(
                "还没有方法钩子。先到「设置 → 方法钩子」创建一个目标应用的方法钩子。",
                12.sp,
                color = C.Muted,
                lineHeight = 18.sp,
            )
            EditorPill("去新建方法钩子", "去新建方法钩子", action = onOpenMethodHooks)
        } else if (expanded) {
            hooks.forEach { hook ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp))
                        .background(if (hook.id == current) C.PrimarySoft else C.Card)
                        .editorAction("选择方法钩子：${hook.id}") {
                            onValue(hook.id)
                            expanded = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        T("${hook.packageName}@${hook.userId}", 14.sp, maxLines = 1)
                        T("${hook.className}#${hook.methodName}", 12.sp,
                            color = C.OnSurfaceVar, maxLines = 1)
                    }
                    T(
                        if (hook.enabled) "已启用" else "未启用",
                        11.sp,
                        color = if (hook.enabled) C.GoodText else C.Muted,
                        maxLines = 1,
                    )
                }
            }
        }
        if (current.isNotBlank() && selected == null) {
            T("当前选择的方法钩子已不存在，请重新选择。", 12.sp, color = C.Bad)
        }
        Spacer(Modifier.height(2.dp))
    }
}
