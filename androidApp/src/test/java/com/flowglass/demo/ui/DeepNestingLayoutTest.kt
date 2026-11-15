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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Five alternating loop/if levels are the regression case from the phone: fixed indentation plus
 * two fixed action buttons used to squeeze the text column down to one character per line.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-440dpi")
class DeepNestingLayoutTest {
    @Test
    fun fiveLevelsKeepAtLeastTheMinimumTextWidth() = verifyLayout(1f)

    @Test
    fun fiveLevelsAtLargeFontKeepTextAndSingleLineAddButtons() = verifyLayout(1.3f)

    private fun verifyLayout(fontScale: Float) {
        val state = AppState()
        val cmd = state.create(auto = true)
        var parent: Long? = null
        repeat(5) { level ->
            val option = CommandCatalog.options(CommandSection.ACTIONS)
                .single { it.typeId == if (level % 2 == 0) "block.loop" else "block.if" }
            if (parent == null) {
                state.addItem(cmd.id, CommandSection.ACTIONS, option)
                parent = requireNotNull(state.find(cmd.id)).actions.last().id
            } else {
                state.addChildItem(cmd.id, parent!!, null, option)
                parent = requireNotNull(requireNotNull(state.find(cmd.id)).actions.findItem(parent!!)).children.last().id
            }
        }
        val blocks = requireNotNull(state.find(cmd.id)).actions.allItems()
            .filter { it.typeId == "block.loop" || it.typeId == "block.if" }
        assertTrue("应构造 5 层嵌套", blocks.size == 5)
        state.openEditor(cmd.id)

        withActivity(state) { activity ->
            val oldFontScale = activity.resources.configuration.fontScale
            activity.resources.configuration.fontScale = fontScale
            activity.resources.updateConfiguration(
                activity.resources.configuration,
                activity.resources.displayMetrics,
            )
            try {
                activity.setContent {
                    Box(Modifier.fillMaxSize()) { CommandEditorHost(state) }
                }
                pump(activity)
                val density = activity.resources.displayMetrics.density
                val minPx = 120f * density
                val maxButtonHeight = 48f * density + 1f
                val found = nodes(activity.window.decorView)
                    .associateBy { it.config.getOrNull(SemanticsProperties.TestTag) }
                blocks.forEach { block ->
                    val node = found["action-text-${block.id}"]
                        ?: error("第 ${blocks.indexOf(block) + 1} 层缺少文字列语义节点")
                    assertTrue(
                        "第 ${blocks.indexOf(block) + 1} 层文字列只有 ${node.boundsInRoot.width}px，应不小于 ${minPx}px",
                        node.boundsInRoot.width >= minPx - 0.5f,
                    )
                    val add = found["block-add-${block.id}"]
                        ?: error("第 ${blocks.indexOf(block) + 1} 层缺少「＋ 添加」按钮")
                    assertTrue(
                        "第 ${blocks.indexOf(block) + 1} 层「＋ 添加」按钮应在单行内（高度 ${add.boundsInRoot.height}px）",
                        add.boundsInRoot.height <= maxButtonHeight,
                    )
                }
            } finally {
                activity.resources.configuration.fontScale = oldFontScale
                activity.resources.updateConfiguration(
                    activity.resources.configuration,
                    activity.resources.displayMetrics,
                )
            }
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
