package com.flowglass.demo.ui

import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 真实执行入口必须把已保存的全局变量与类型元数据交给引擎；否则 get('x') 读到未设置，
 * 跨运行累加这类脚本会在真机上失败，而单元测试里直接调用引擎却能通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExecutionGlobalVariablesTest {
    private fun script(text: String) = Cmd(
        id = 1,
        auto = false,
        ic = Ic.Bolt,
        title = "读全局变量",
        desc = "",
        actions = listOf(
            CommandItem(1, "执行脚本", """{"script":"$text"}""", Ic.Bolt, "action.script"),
        ),
    )

    @Test
    fun aSavedGlobalIsVisibleInsideTheScriptAtTheRealEntryPoint() = runBlocking {
        val state = AppState()
        state.bindExecutionContext(RuntimeEnvironment.getApplication())
        state.setGlobal("count", "7")
        val cmd = script("get('count')")
        state.oneTaps.add(cmd)

        state.execute(cmd, this, "测试")
        withTimeout(10_000) {
            while (state.logs.isEmpty()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val log = state.logs.first()
        assertTrue("执行应成功：${log.detail}", log.ok)
        assertTrue("脚本必须读到已保存的全局变量：${log.detail}", log.detail.contains("脚本返回：7"))
    }
}
