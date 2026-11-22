package com.flowglass.demo

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * BG-007：vendored 的 Apache-2.0 代码（backdrop/）必须在 APK 里带许可证副本，
 * 只放在仓库根目录不算分发合规。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledLicensesTest {
    @Test
    fun theVendoredApacheLicenseShipsInsideTheApk() {
        val context = RuntimeEnvironment.getApplication()
        val text = context.assets.open("licenses/AndroidLiquidGlass-LICENSE.txt")
            .bufferedReader()
            .use { it.readText() }
        assertTrue("许可证正文缺失", text.contains("Apache License"))
        assertTrue("许可证版本缺失", text.contains("Version 2.0"))
        assertTrue(
            "必须带完整的条款正文，不能只放一句声明",
            text.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"),
        )
    }
}
