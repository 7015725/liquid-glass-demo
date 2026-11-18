package com.flowglass.demo.system.trigger

import android.os.Looper
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.Ic
import com.flowglass.engine.trigger.TriggerBinding
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TriggerRuleExecutorTest {
    private val rule = TriggerRule(
        id = "rule",
        cmdId = 7L,
        name = "rule",
        enabled = true,
        triggers = listOf(TriggerComponent("trigger.screen_on", "屏幕点亮")),
    )
    private val cmd = Cmd(7L, true, Ic.Bolt, "测试", "", actions = emptyList())

    @Test
    fun executeAndStateReadsUseInjectedMainDispatcher() {
        val mainThreadSeen = AtomicBoolean(true)
        val executor = TriggerRuleExecutor(
            mainDispatcher = Dispatchers.Main,
            find = {
                mainThreadSeen.set(Looper.myLooper() == Looper.getMainLooper())
                cmd
            },
            activeCountFor = { 0 },
            cancelFor = {},
            startFor = { _, _, _, _ ->
                mainThreadSeen.set(mainThreadSeen.get() && Looper.myLooper() == Looper.getMainLooper())
                Job().apply { complete() }
            },
            onFinished = { _, _ -> },
        )
        val result = AtomicReference<Boolean>()
        val background = Thread {
            result.set(
                runBlocking(Dispatchers.Default) {
                    executor.execute(
                        rule = rule,
                        binding = TriggerBinding(rule, 0, rule.triggers.single(), null),
                        reason = "test",
                        variables = emptyMap(),
                    )
                },
            )
        }
        background.start()
        // The test thread owns Robolectric's main looper; pump it while the background coroutine
        // waits for Dispatchers.Main.
        val deadline = System.currentTimeMillis() + 5_000
        while (background.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        background.join(2_000)
        assertTrue("execute did not finish", !background.isAlive)
        assertTrue("state access happened outside the injected main dispatcher", mainThreadSeen.get())
        assertTrue("execute returned false", result.get())
    }
}
