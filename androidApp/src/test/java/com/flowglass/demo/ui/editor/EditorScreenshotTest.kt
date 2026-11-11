package com.flowglass.demo.ui.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.flowglass.engine.Rule
import org.junit.Rule as JunitRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScreenshotTest {
    @get:JunitRule val compose = createComposeRule()

    @Test fun captureActualComposeEditor() {
        compose.setContent { RuleEditorScreen(Rule(name = "到家后切换静音", description = "编写 WiFi 触发、时间条件和静音动作；这里只保存，不执行。"), emptyList(), {}, {}) }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val target = File("build/editor-screenshots/editor-native.png")
        requireNotNull(target.parentFile).mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        org.junit.Assert.assertTrue(target.length() > 0)
    }
}
