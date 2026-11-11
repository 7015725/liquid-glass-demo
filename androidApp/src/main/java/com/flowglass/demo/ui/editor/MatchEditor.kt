package com.flowglass.demo.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.ui.C
import com.flowglass.demo.ui.T
import com.flowglass.engine.*
import com.kyant.shapes.RoundedRectangle
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun MatchEditor(spec: MatchSpec, components: List<Component>, path: String, inputs: EditorInputs, onChange: (MatchSpec) -> Unit) {
    EditorCard("匹配规则") {
        EditorChoices("匹配方式", listOf("any" to "任一", "all" to "全部", "custom" to "自定义"), spec.mode, path) { mode ->
            inputs.attempt(path) {
                onChange(spec.copy(mode = mode, atLeast = if (mode == "custom") spec.atLeast ?: if (spec.tree == null) 1 else null else null,
                    tree = if (mode == "custom") spec.tree else null))
                inputs.clear("$path.atLeast")
            }
        }
        EditorField("匹配窗口（秒）", inputs.value("$path.windowSeconds", spec.windowSeconds.toString()), { text ->
            inputs.edit("$path.windowSeconds", text, "int32") { onChange(spec.copy(windowSeconds = it.jsonPrimitive.int)) }
        }, "$path-window", numeric = true, hint = "触发事件在窗口内组合；条件按当前状态匹配。")
        if (spec.mode == "custom") {
            EditorChoices("自定义形式", listOf("count" to "至少 N 个", "tree" to "可视化逻辑树"), if (spec.tree == null) "count" else "tree", path) {
                inputs.attempt(path) {
                    onChange(if (it == "tree") spec.copy(atLeast = null, tree = spec.tree ?: MatchNode(ref = components.firstOrNull()?.id))
                    else spec.copy(atLeast = 1, tree = null))
                    inputs.clear("$path.atLeast")
                }
            }
            if (spec.tree == null) EditorField("至少匹配数量 N", inputs.value("$path.atLeast", spec.atLeast?.toString() ?: ""), { text ->
                inputs.edit("$path.atLeast", text, "int32") { onChange(spec.copy(atLeast = it.jsonPrimitive.int, tree = null)) }
            }, "$path-at-least", numeric = true, hint = "1 到当前组件数量；不会自动修正非法值。")
            else {
                T("与 AND / 或 OR / 非 NOT，叶节点引用组件 ID。删除被引用的组件会在保存时报告错误。", 12.sp, color = C.OnSurfaceVar)
                MatchTreeNode(requireNotNull(spec.tree), components, "root", 1) { node ->
                    inputs.attempt(path) { onChange(spec.copy(atLeast = null, tree = node)) }
                }
            }
        }
    }
}

@Composable
private fun MatchTreeNode(node: MatchNode, components: List<Component>, path: String, depth: Int, onChange: (MatchNode) -> Unit) {
    Column(Modifier.fillMaxWidth().background(if (depth % 2 == 0) C.Card else C.PrimarySoft, RoundedRectangle(18.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T("第 $depth 层 · ${node.op.uppercase()}", 13.sp)
        val choices = if (depth < EDITOR_MAX_DEPTH) listOf("leaf" to "引用", "and" to "与", "or" to "或", "not" to "非") else listOf("leaf" to "引用")
        EditorChoices("节点类型", choices, node.op, "tree-$path") { op ->
            if (op != node.op) onChange(when (op) {
                "leaf" -> MatchNode(ref = node.ref ?: components.firstOrNull()?.id)
                "not" -> MatchNode(op = "not", children = listOf(node))
                else -> MatchNode(op = op, children = if (node.op in setOf("and", "or")) node.children else listOf(node))
            })
        }
        if (node.op == "leaf") {
            if (components.isEmpty()) T("先添加组件，才能选择引用。", 12.sp, color = C.Bad)
            if (node.ref != null && components.none { it.id == node.ref }) T("引用已失效：${node.ref}", 12.sp, color = C.Bad)
            EditorChoices("引用组件", components.mapIndexed { i, component -> component.id to "${i + 1}. ${ComponentRegistry.find(component.type)?.name ?: component.type}" }, node.ref ?: "", "tree-$path-ref") {
                onChange(node.copy(ref = it))
            }
        } else if (depth >= EDITOR_MAX_DEPTH) {
            T("已达树深度上限 $EDITOR_MAX_DEPTH，请改为引用节点。", 12.sp, color = C.Bad)
        } else {
            node.children.forEachIndexed { index, child ->
                MatchTreeNode(child, components, "$path-$index", depth + 1) { changed ->
                    onChange(node.copy(children = node.children.mapIndexed { i, old -> if (i == index) changed else old }))
                }
                if (node.op != "not") EditorButton("删除子节点 ${index + 1}", "tree-$path-remove-$index") {
                    onChange(node.copy(children = node.children.filterIndexed { i, _ -> i != index }))
                }
            }
            if ((node.op in setOf("and", "or") && node.children.size < 20) || (node.op == "not" && node.children.isEmpty())) {
                EditorButton("添加引用节点", "tree-$path-add") {
                    onChange(node.copy(children = node.children + MatchNode(ref = components.firstOrNull()?.id)))
                }
            }
        }
    }
}
