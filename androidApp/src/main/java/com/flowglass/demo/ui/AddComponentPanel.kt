package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.engine.Tier
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/**
 * Catalog picker shared by the editor's "添加" panel and the "换成目录动作" list.
 *
 * Everything here is derived from [com.flowglass.engine.ComponentMeta]: the options, their
 * categories, the tier badge and whether the row is selectable. There is deliberately no
 * hand-written component list, so triggers/actions added by another branch appear on their own.
 */
@Composable
internal fun ComponentPicker(
    section: CommandSection,
    activeTier: Tier,
    modifier: Modifier = Modifier,
    onPick: (CommandOption) -> Unit,
) {
    val all = remember(section) { CommandCatalog.options(section) }
    val categories = remember(all) { all.map { it.categoryName }.distinct() }
    var query by remember(section) { mutableStateOf("") }
    var category by remember(section) { mutableStateOf<String?>(null) }
    val needle = query.trim()
    val visible = all.filter { option ->
        (category == null || option.categoryName == category) &&
            (needle.isEmpty() || option.name.contains(needle, true) ||
                option.categoryName.contains(needle, true) || option.detail.contains(needle, true))
    }

    Column(modifier.testTag("component-picker"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PickerSearch(query) { query = it }
        FilterChips(
            listOf("全部") + categories,
            categories.indexOf(category) + 1,
            { index -> category = categories.getOrNull(index - 1) },
            Modifier.testTag("component-categories"),
        )
        if (visible.isEmpty()) {
            EmptyState(
                Ic.Search, "没有匹配的组件",
                if (needle.isEmpty()) "这个分类下暂时没有可添加的组件" else "换个说法，或清空搜索词",
                actionLabel = "清空筛选",
                actionTag = "component-clear-filter",
            ) { query = ""; category = null }
            return@Column
        }
        var row = 0
        if (category == null && needle.isEmpty()) {
            visible.groupBy { it.categoryName }.forEach { (name, options) ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(CommandCatalog.categoryIcon(name, section.kindOf()), C.Muted, 15.dp)
                    Spacer(Modifier.width(6.dp))
                    T(name, 12.sp, FontWeight.Bold, C.Muted, modifier = Modifier.weight(1f))
                    T("${options.size}", 12.sp, color = C.Muted)
                }
                options.forEach { option -> ComponentRow(option, activeTier, row++, onPick) }
            }
        } else {
            visible.forEach { option -> ComponentRow(option, activeTier, row++, onPick) }
        }
    }
}

private fun CommandSection.kindOf() = when (this) {
    CommandSection.TRIGGERS -> com.flowglass.engine.ComponentKind.TRIGGER
    CommandSection.CONDITIONS -> com.flowglass.engine.ComponentKind.CONDITION
    CommandSection.ACTIONS -> com.flowglass.engine.ComponentKind.ACTION
}

@Composable
private fun PickerSearch(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(Capsule())
            .background(Color.White.copy(alpha = 0.78f)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(Ic.Search, C.Muted, 18.dp)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (query.isEmpty()) T("搜索组件名称或分类", 14.sp, color = C.Muted)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = C.OnSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(C.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().testTag("component-search"),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(30.dp).clip(Capsule()).background(C.Tonal)
                    .editorAction("清空搜索") { onQuery("") },
                contentAlignment = Alignment.Center,
            ) { T("✕", 12.sp, FontWeight.Bold, C.OnSurfaceVar) }
        }
    }
}

@Composable
private fun ComponentRow(option: CommandOption, activeTier: Tier, index: Int, onPick: (CommandOption) -> Unit) {
    val locked = option.minTier.ordinal > activeTier.ordinal
    val blocked = locked || option.comingSoon
    val tag = option.typeId ?: option.name
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("component-option-$tag").staggerIn(index.coerceAtMost(10))
            .clip(RoundedRectangle(22.dp)).background(if (blocked) C.Tonal else C.Card)
            .then(
                if (blocked) Modifier.semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(option.name)
                        append("，")
                        append(if (option.comingSoon) "即将支持" else option.minTier.badgeLabel)
                        append("，当前不可选择")
                    }
                    disabled()
                } else Modifier.editorAction(option.name) { onPick(option) }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(PolygonShape(M3EShapes.Clover4))
                .background(if (blocked) C.TonalStrong else C.PrimarySoft),
            contentAlignment = Alignment.Center,
        ) { LineIcon(option.ic, if (blocked) C.Muted else C.Primary, 20.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(option.name, 15.sp, FontWeight.SemiBold, if (blocked) C.OnSurfaceVar else C.OnSurface, maxLines = 1)
            if (option.detail.isNotBlank()) {
                T(option.detail, 12.sp, color = C.Muted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            option.comingSoon -> BadgePill("即将支持", C.OnSurfaceVar, C.TonalStrong)
            locked -> Row(verticalAlignment = Alignment.CenterVertically) {
                LineIcon(Ic.Lock, C.Muted, 14.dp)
                Spacer(Modifier.width(4.dp))
                T(option.minTier.badgeLabel, 11.sp, FontWeight.Medium, C.Muted)
            }
            option.minTier != Tier.NORMAL -> BadgePill(option.minTier.badgeLabel, C.Primary, C.PrimarySoft)
            else -> LineIcon(Ic.Plus, C.Primary, 20.dp)
        }
    }
}
