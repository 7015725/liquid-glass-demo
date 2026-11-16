package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One valid destination in the "移动到…" tree. */
internal data class MoveTarget(
    val parentId: Long?,
    val branch: Int?,
    val label: String,
    val path: String,
    val detail: String,
    val depth: Int,
)

/** Builds the destination tree without exposing the source or any of its descendants. */
internal fun moveTargets(actions: List<CommandItem>, sourceId: Long): List<MoveTarget> {
    val out = mutableListOf<MoveTarget>()
    out += MoveTarget(
        parentId = null, branch = null, label = "根层", path = "根层",
        detail = "${actions.size} 个步骤", depth = 0,
    )
    fun walk(items: List<CommandItem>, path: List<String>, depth: Int) {
        for (item in items) {
            if (item.id == sourceId) continue
            if (!item.canHostSteps()) continue
            val nextPath = path + item.name
            val bodyDetail = when {
                item.children.isEmpty() -> "空块 · 放到第一个步骤"
                item.collapsed -> "已折叠 · ${item.children.size} 步"
                else -> "${item.children.size} 步"
            }
            out += MoveTarget(
                parentId = item.id, branch = null, label = item.name,
                path = nextPath.joinToString(" › "), detail = "块主体 · $bodyDetail", depth = depth,
            )
            item.branches.forEachIndexed { index, branch ->
                if (branch.conditions.isNotEmpty()) return@forEachIndexed
                val branchLabel = "否则"
                val branchPath = nextPath + branchLabel
                val branchDetail = when {
                    branch.children.isEmpty() -> "空分支 · 放到第一个步骤"
                    item.collapsed -> "已折叠 · ${branch.children.size} 步"
                    else -> "${branch.children.size} 步"
                }
                out += MoveTarget(
                    parentId = item.id, branch = index, label = branchLabel,
                    path = branchPath.joinToString(" › "), detail = "分支 · $branchDetail", depth = depth + 1,
                )
            }
            walk(item.children, nextPath, depth + 1)
            item.branches.forEachIndexed { index, branch ->
                if (branch.conditions.isNotEmpty()) return@forEachIndexed
                walk(branch.children, nextPath + "否则", depth + 2)
            }
        }
    }
    walk(actions, listOf("根层"), 1)
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoveToPanel(
    state: AppState,
    cmd: Cmd,
    sourceId: Long,
    onDone: () -> Unit,
) {
    val source = cmd.actions.findActionNode(sourceId) ?: return
    val targets = remember(cmd.actions, sourceId) { moveTargets(cmd.actions, sourceId) }
    T(
        "把「${source.name}」放到…", 13.sp, color = C.OnSurfaceVar,
        lineHeight = 19.sp, modifier = Modifier.fillMaxWidth(),
    )
    T(
        "先选目标，再选开头或末尾；中间位置可用上移 / 下移调整。", 12.sp, color = C.Muted,
        lineHeight = 18.sp, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(2.dp))
    targets.forEach { target ->
        Column(
            Modifier.fillMaxWidth()
                .padding(start = (target.depth.coerceAtMost(3) * 12).dp)
                .background(C.Card, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                T(target.label, 15.sp, FontWeight.SemiBold, C.OnSurface, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                T(target.detail, 12.sp, color = C.Muted, maxLines = 1, modifier = Modifier.weight(1f))
            }
            T(target.path, 11.sp, color = C.Muted, maxLines = 1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorPill("放到开头", "把「${source.name}」放到${target.path}开头") {
                    chooseTarget(state, cmd, source, target, start = true, onDone = onDone)
                }
                EditorPill("放到末尾", "把「${source.name}」放到${target.path}末尾") {
                    chooseTarget(state, cmd, source, target, start = false, onDone = onDone)
                }
            }
        }
    }
}

private fun chooseTarget(
    state: AppState,
    cmd: Cmd,
    source: CommandItem,
    target: MoveTarget,
    start: Boolean,
    onDone: () -> Unit,
) {
    val list = cmd.actions.actionChildrenOf(target.parentId, target.branch) ?: return
    val sourceInTarget = list.any { it.id == source.id }
    val index = when {
        start -> 0
        sourceInTarget -> (list.size - 1).coerceAtLeast(0)
        else -> list.size
    }
    state.moveActionTo(cmd.id, source.id, target.parentId, target.branch, index)
    onDone()
}
