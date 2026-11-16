package com.flowglass.demo.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.graphics.Insets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * `imePadding()` shrinks the viewport but does not reveal a field that is below it. This test
 * focuses a field near the bottom of a scroll container, dispatches a real IME inset, and checks
 * that [imeAutoScroll] asked the scroll parent to bring it back into view.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImeInsetsTest {
    @Test
    fun focusedFieldScrollsWhenImeAppears() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val focus = FocusRequester()
        val scroll = ScrollState(0)
        try {
            activity.setContent {
                ImeTestContent(focus, scroll)
            }
            pump(activity)

            focus.requestFocus()
            pump(activity)
            assertTrue("初始状态应还有可滚动空间", scroll.maxValue > 0)
            val beforeIme = scroll.value
            assertTrue("字段初始应在可视区内，实际滚动 $beforeIme", beforeIme == 0)

            dispatchIme(activity, 1_000)
            pump(activity, cycles = 20)

            assertTrue(
                "键盘出现后字段应继续滚入缩小后的可视区，before=$beforeIme after=${scroll.value}",
                scroll.value > beforeIme,
            )
        } finally {
            activity.setContent { }
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Composable
    private fun ImeTestContent(focus: FocusRequester, scroll: ScrollState) {
        Box(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Spacer(Modifier.height(1_500.dp))
                BasicTextField(
                    value = "field",
                    onValueChange = {},
                    modifier = Modifier.imeAutoScroll().focusRequester(focus),
                    singleLine = true,
                    textStyle = TextStyle(),
                )
                Spacer(Modifier.height(1_000.dp))
            }
        }
    }

    private fun dispatchIme(activity: ComponentActivity, bottom: Int) {
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
            .build()
        activity.window.decorView.dispatchApplyWindowInsets(insets)
    }

    private fun pump(activity: ComponentActivity, cycles: Int = 10) {
        repeat(cycles) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
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
}
