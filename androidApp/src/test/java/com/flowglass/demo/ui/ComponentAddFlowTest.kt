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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import java.time.Duration

/** Picking a parameterized component must open a draft page, not insert a half-edited item. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComponentAddFlowTest {
    @Test
    fun cancelNewComponentDoesNotInsertIt() {
        val state = AppState()
        val command = state.create(auto = true)
        state.openEditor(command.id)

        withActivity(state) { activity ->
            click(activity, "添加任务")
            click(activity, "调节亮度")

            assertNotNull("选择带参数的组件后必须打开独立参数页", actionEditor(activity))
            assertEquals("参数页打开时不能插入半成品", 0, commandOf(state, command.id).actions.size)

            clickInEditor(activity, "返回")
            clickInDialog(activity, "放弃修改")
            assertNull(actionEditor(activity))
            assertEquals("返回并放弃后指令必须保持原样", 0, commandOf(state, command.id).actions.size)
        }
    }

    @Test
    fun doneInsertsNewComponentOnce() {
        val state = AppState()
        val command = state.create(auto = true)
        state.openEditor(command.id)

        withActivity(state) { activity ->
            click(activity, "添加任务")
            click(activity, "调节亮度")
            assertNotNull(actionEditor(activity))
            assertEquals(0, commandOf(state, command.id).actions.size)

            click(activity, "完成")
            val actions = commandOf(state, command.id).actions
            assertEquals("完成时只插入一次", 1, actions.size)
            assertEquals("调节亮度", actions.single().name)
            assertNull(state.componentDraft)
        }
    }

    private fun commandOf(state: AppState, id: Long) = requireNotNull(state.find(id))

    private fun withActivity(state: AppState, block: (ComponentActivity) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {
                Box(Modifier.fillMaxSize()) {
                    CommandEditorHost(state)
                    ActionEditorHost(state)
                }
            }
            pump(controller.get())
            block(controller.get())
        } finally {
            controller.get().setContent { }
            state.cancelComponentDraft()
            state.editing = null
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun clickInEditor(activity: ComponentActivity, label: String) {
        val root = requireNotNull(actionEditor(activity)) { "参数页未打开" }
        val node = descendants(root).firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text == label } == true
        } ?: error("参数页内找不到 $label")
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        pump(activity, cycles = 24)
    }

    private fun clickInDialog(activity: ComponentActivity, label: String) {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "没有确认对话框" }
        val node = nodes(requireNotNull(dialog.window).decorView).firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text == label } == true
        } ?: error("对话框内找不到 $label")
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        pump(activity, cycles = 24)
    }

    private fun click(activity: ComponentActivity, label: String) {
        val node = nodes(activity.window.decorView).firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text == label } == true
        } ?: error("找不到 $label")
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        pump(activity, cycles = 24)
    }

    private fun actionEditor(activity: ComponentActivity): SemanticsNode? =
        nodes(activity.window.decorView).singleOrNull {
            it.config.getOrNull(SemanticsProperties.TestTag) == "action-editor"
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

    private fun nodes(view: View): List<SemanticsNode> =
        roots(view).flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)
}
