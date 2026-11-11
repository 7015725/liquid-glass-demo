package com.flowglass.demo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowglass.demo.liquid.LiquidButton
import com.flowglass.demo.ui.editor.EditorField
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Test-only showcase. No preview screen or navigation entry is shipped in the application. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassSheetPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativePreviewAt360dp() = capture(1f, "glass-sheet-360dp.png")
    @Test fun nativePreviewAt360dpWithLargeText() = capture(1.3f, "glass-sheet-360dp-font130.png")

    private fun capture(fontScale: Float, filename: String) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                Box(Modifier.fillMaxSize().background(C.Bg).testTag("preview-background")) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 56.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        T("流光自动化", 30.sp, FontWeight.Bold)
                        T("阶段 1 · 独立组件预览", 14.sp, color = C.OnSurfaceVar)
                        T("业务页面尚未接入", 13.sp, color = C.Muted)
                    }
                    GlassSheet(true, "通用底部面板", {}) { backdrop ->
                        T("底部滑出 · 遮罩与返回键关闭", 14.sp, color = C.OnSurfaceVar)
                        T("这里是可滚动的内容区域，按钮沿用现有液态玻璃组件。", 14.sp, lineHeight = 21.sp)
                        Row(Modifier.fillMaxWidth().background(C.WarnSoft).padding(14.dp)) {
                            T("组件预览，不执行任何指令", 13.sp, color = C.Warn)
                        }
                        var name by remember { mutableStateOf("示例内容") }
                        EditorField("表单示例", name, { name = it }, "preview-field")
                        LiquidButton({}, backdrop, modifier = Modifier.fillMaxWidth().testTag("preview-primary"),
                            isInteractive = false, surfaceColor = C.Primary) {
                            T("主要操作", 14.sp, FontWeight.SemiBold, androidx.compose.ui.graphics.Color.White)
                        }
                        LiquidButton({}, backdrop, modifier = Modifier.fillMaxWidth(),
                            isInteractive = false, surfaceColor = C.Tonal) {
                            T("次要操作", 14.sp, color = C.OnSurfaceVar)
                        }
                        T("面板中的输入、滚动位置与返回行为单独验证。", 12.sp, color = C.OnSurfaceVar, lineHeight = 18.sp)
                    }
                }
            }
        }
        compose.onNodeWithTag("glass-sheet-close").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("preview-primary").assertIsDisplayed()
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("通用底部面板").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertFalse("Header must not clip or ellipsize at $fontScale", layout.single().hasVisualOverflow)

        // Compose captures separate Android windows separately. Composite their actual pixels
        // in window order; neither image is HTML, a mockup, nor a generated replacement.
        val background = compose.onNodeWithTag("preview-background").captureToImage().asAndroidBitmap()
        val overlay = compose.onNodeWithTag("glass-sheet-overlay").captureToImage().asAndroidBitmap()
        assertEquals(background.width, overlay.width)
        assertEquals(background.height, overlay.height)
        val combined = Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888)
        Canvas(combined).apply { drawBitmap(background, 0f, 0f, null); drawBitmap(overlay, 0f, 0f, null) }
        val target = File("build/glass-sheet-screenshots/$filename")
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        target.outputStream().use { assertTrue(combined.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(target.length() > 0)
    }
}
