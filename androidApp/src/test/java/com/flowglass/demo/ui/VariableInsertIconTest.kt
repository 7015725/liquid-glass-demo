package com.flowglass.demo.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.flowglass.engine.VariableScope
import com.flowglass.engine.VariableType
import org.junit.Assert.assertFalse
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
@Config(sdk = [35])
class VariableInsertIconTest {
    private val options = listOf(
        VariableOption("上一步结果", VariableScope.BUILTIN, VariableType.TEXT, "上一步执行结果", "运行时提供", 0),
        VariableOption("局部值", VariableScope.LOCAL, VariableType.TEXT, "局部变量", "x", 0),
        VariableOption("全局值", VariableScope.GLOBAL, VariableType.TEXT, "全局变量", "y", 0),
    )

    @Test
    fun variableInsertLivesOnTheRightOfTheFieldAndNoLongerUsesABelowButton() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            activity.setContent {
                VariableTextField("A31_START", "param-value", options, multiline = false) {}
            }
            pump(activity)
            val nodes = nodes(activity.window.decorView)
            val field = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "param-value" }
            val icon = nodes.single {
                it.config.getOrNull(SemanticsProperties.TestTag) == "param-value-insert-variable"
            }
            assertTrue("变量图标应在输入框右侧", icon.boundsInRoot.left >= field.boundsInRoot.right)
            assertTrue(
                "变量图标必须可点击",
                icon.config[SemanticsActions.OnClick].action != null,
            )
            assertTrue(
                "变量图标必须有可读说明",
                icon.config.getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty().any { it == "插入变量" },
            )
            assertFalse(
                "输入框下方不应再保留文字按钮",
                nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .any { it.text == "插入变量" },
            )
        } finally {
            controller.get().setContent { }
            controller.destroy()
        }
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
