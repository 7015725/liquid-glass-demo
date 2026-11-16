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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-440dpi")
class BlockPagesLayoutTest {
    @Test
    fun rootShowsCardOnlyAndCardOpensOneLevelPage() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS,
            CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "block.loop" })
        val block = requireNotNull(state.find(cmd.id)).actions.single()
        state.addConditionItem(cmd.id, block.id, null,
            CommandCatalog.options(CommandSection.CONDITIONS).single { it.typeId == "condition.charging" })
        state.addChildItem(cmd.id, block.id, null,
            CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.toast" })
        val child = requireNotNull(state.find(cmd.id)).actions.single().children.single()
        state.openEditor(cmd.id)

        withActivity(state) { activity ->
            activity.setContent { Box(Modifier.fillMaxSize()) { CommandEditorHost(state) } }
            pump(activity)
            var found = find(activity)
            val card = found["block-card-${block.id}"]
            assertNotNull("根页应显示块卡片", card)
            org.junit.Assert.assertNull("根页不应展开块内步骤", found["action-text-${child.id}"])
            // The root card is the whole block UI: no condition rows are expanded there.
            assertTrue("根页不应展开块内条件",
                nodes(activity.window.decorView).none {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("编辑条件") } == true
                })
            val click = card!!.config.getOrNull(SemanticsActions.OnClick)?.action
            assertNotNull("块卡片应可点击进入", click)
            assertTrue(requireNotNull(click).invoke())
            pump(activity)

            found = find(activity)
            assertNotNull("应进入块页", found["block-page"])
            assertNotNull("块页应显示内部步骤", found["action-text-${child.id}"])
            assertNotNull("块页应有添加按钮", found["block-page-add-${block.id}"])
            assertEqualsPath(state, listOf(block.id))

            state.backBlock()
            pump(activity)
            found = find(activity)
            assertNotNull("返回后应回到根页卡片", found["block-card-${block.id}"])
            assertTrue(state.blockPath.isEmpty())
        }
    }

    private fun assertEqualsPath(state: AppState, expected: List<Long>) {
        assertTrue("路径应为 $expected，实际 ${state.blockPath}", state.blockPath == expected)
    }

    private fun find(activity: ComponentActivity): Map<String?, SemanticsNode> =
        nodes(activity.window.decorView).associateBy { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun withActivity(state: AppState, block: (ComponentActivity) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            block(controller.get())
        } finally {
            controller.get().setContent { }
            state.closeEditor()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun pump(activity: ComponentActivity, cycles: Int = 12) {
        repeat(cycles) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val decor = activity.window.decorView
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, 1080, 2400)
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
