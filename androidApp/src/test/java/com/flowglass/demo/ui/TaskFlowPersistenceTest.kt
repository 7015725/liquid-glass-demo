package com.flowglass.demo.ui

import com.flowglass.engine.OnError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The failure policy is part of the task, so it must survive the document round-trip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskFlowPersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun onErrorSurvivesDocumentRoundTrip() {
        val cmd = Cmd(
            id = 42, auto = false, ic = Ic.Bolt, title = "任务", desc = "",
            actions = listOf(
                CommandItem(43, "开关 WiFi", "开启", Ic.Wifi, "action.wifi_switch", OnError.CONTINUE),
                CommandItem(44, "调节亮度", "50%", Ic.Gauge, "action.set_brightness", OnError.RETRY),
                CommandItem(45, "复制到剪贴板", "x", Ic.Copy, "action.copy_clipboard", OnError.STOP),
            ),
        )
        val document = PersistedAppState(
            autoCmds = emptyList(), oneTaps = listOf(cmd), logs = emptyList(), running = true, mode = 1,
        ).toRuleDocument()
        assertEquals(
            listOf(OnError.CONTINUE, OnError.RETRY, OnError.STOP),
            document.rules.single().actions.map { it.onError },
        )

        val restored = document.toPersistedAppState().oneTaps.single()
        assertEquals(listOf(OnError.CONTINUE, OnError.RETRY, OnError.STOP), restored.actions.map { it.onError })
    }

    @Test fun onErrorDefaultsToStop() {
        assertEquals(OnError.STOP, CommandItem(1, "a", "", Ic.Bolt, "action.delay").onError)
    }
}
