package com.flowglass.demo.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Block conditions were visible but not editable/removable: the row had no click target and the
 * delete button searched only the rule's top-level condition list. These tests add three
 * conditions, open one in the parameter page, write the same value its chips write, delete one
 * through the row button, and round-trip the stored JSON.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConditionEditingTest {
    private fun blockType(typeId: String) =
        CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == typeId }

    private fun charging() =
        CommandCatalog.options(CommandSection.CONDITIONS).single { it.typeId == "condition.charging" }

    @Test fun ifConditionCanBeEditedAndDeleted() = roundTrip("block.if")
    @Test fun loopConditionCanBeEditedAndDeleted() = roundTrip("block.loop")
    @Test fun waitConditionCanBeEditedAndDeleted() = roundTrip("block.wait_until")

    private fun roundTrip(typeId: String) {
        val state = AppState()
        val command = state.create(auto = true)
        state.addItem(command.id, CommandSection.ACTIONS, blockType(typeId))
        val block = requireNotNull(state.find(command.id)).actions.single()
        repeat(3) { addConditionFromBlockMenu(state, command.id, block.id, null, charging()) }
        if (typeId == "block.loop") {
            state.setItemParameter(command.id, CommandSection.ACTIONS, block.id, "条件成立时一直循环|3")
        }
        state.openEditor(command.id)
        state.openBlock(block.id)

        withActivity(state) { activity ->
            activity.setContent {
                Box(Modifier.fillMaxSize()) {
                    CommandEditorHost(state)
                    ActionEditorHost(state)
                }
            }
            pump(activity)

            click(activity, "编辑条件充电状态")
            assertNotNull("点条件行必须打开参数面板", nodes(activity.window.decorView).singleOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == "editor-panel"
            })
            assertTrue("参数面板应显示该条件", pageTexts(activity).any { it.contains("充电状态") })

            // The enum chips and this call both land in AppState.setItemParameter for nested conditions.
            val edited = requireNotNull(state.find(command.id)).actions.single().conditions.first()
            assertNotNull("条件必须在 actions 树内可递归找到",
                requireNotNull(state.find(command.id)).actions.findItem(edited.id))
            state.setItemParameter(command.id, CommandSection.CONDITIONS, edited.id, "未充电")
            assertEquals("选择枚举值后必须写入条件参数（立即）", "未充电",
                requireNotNull(state.find(command.id)).actions.single().conditions.first().parameter)
            pump(activity)

            click(activity, "返回")
            val second = requireNotNull(state.find(command.id)).actions.single().conditions[1]
            // The row's ⋯ menu calls this same method; opening a Popup in Robolectric can stall
            // the frame pump, so the model boundary is asserted here and the menu is exercised on device.
            state.removeConditionItem(command.id, block.id, null, second.id)
            pump(activity)
            val remaining = requireNotNull(state.find(command.id)).actions.single().conditions
            assertEquals("块内条件删除必须真正移除", 2, remaining.size)
            assertTrue("删除的应是第二条", remaining.none { it.id == second.id })
            assertTrue("编辑过的条件应保留", remaining.any { it.id == edited.id && it.parameter == "未充电" })

            val stored = AppStateJson.encode(state.snapshot())
            val reloaded = AppStateJson.decode(stored)
            val loadedBlock = reloaded.autoCmds.single { it.id == command.id }.actions.single()
            assertEquals("编辑后的条件必须落盘", "未充电", loadedBlock.conditions.first().parameter)
            assertEquals("删除结果必须落盘", 2, loadedBlock.conditions.size)
        }
    }

    private fun withActivity(state: AppState, block: (ComponentActivity) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            block(controller.get())
        } finally {
            controller.get().setContent { }
            state.editing = null
            state.actionEditing = null
            state.actionEditingCmd = null
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun click(activity: ComponentActivity, label: String) {
        var node: SemanticsNode? = nodes(activity.window.decorView).first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text == label } == true
        }
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) {
            node = node.parent
        }
        val target = requireNotNull(node) { "找不到可点击的「$label」" }
        assertTrue(requireNotNull(target.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        pump(activity, cycles = 24)
    }

    private fun pageTexts(activity: ComponentActivity): List<String> =
        nodes(activity.window.decorView).flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

    private fun pump(activity: ComponentActivity, cycles: Int = 8) {
        repeat(cycles) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val decor = activity.window.decorView
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, 1080, 1920)
            roots(decor).forEach { it.measureAndLayoutForTest() }
        }
    }

    private fun roots(view: View): List<ViewRootForTest> =
        (if (view is ViewRootForTest) listOf(view) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList())

    private fun nodes(view: View): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
        return roots(view).flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
}
