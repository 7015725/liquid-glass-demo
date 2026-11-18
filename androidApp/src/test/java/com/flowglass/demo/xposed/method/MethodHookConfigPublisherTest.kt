package com.flowglass.demo.xposed.method

import com.flowglass.demo.ui.AppState
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import com.flowglass.demo.ui.snapshot
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ParameterCodec
import com.flowglass.engine.methodhook.AppMethodCall
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MethodHookConfigPublisherTest {
    private val meta = requireNotNull(BuiltInComponents.registry().find(AppMethodCall.TYPE_ID))

    private fun trigger(id: Long) = CommandItem(
        id = id,
        name = "应用方法调用",
        parameter = ParameterCodec.formatComponent(
            meta,
            mapOf(
                AppMethodCall.KEY_PACKAGE to JsonPrimitive("com.example.target"),
                AppMethodCall.KEY_CLASS to JsonPrimitive("com.example.Target"),
                AppMethodCall.KEY_METHOD to JsonPrimitive("submit"),
                AppMethodCall.KEY_PARAM_TYPES to JsonPrimitive("java.lang.String"),
                AppMethodCall.KEY_EXTRACTIONS to JsonPrimitive("[]"),
            ),
        ),
        ic = Ic.Grid,
        typeId = AppMethodCall.TYPE_ID,
    )

    private fun command(
        id: Long,
        enabled: Boolean,
        auto: Boolean = true,
        triggers: List<CommandItem> = listOf(trigger(id + 100)),
    ) = Cmd(
        id = id,
        auto = auto,
        ic = Ic.Bolt,
        title = "测试 $id",
        desc = "",
        triggers = triggers,
        actions = emptyList(),
        enabled = enabled,
    )

    private fun snapshot(vararg commands: Cmd) = AppState().apply {
        commands.forEach { if (it.auto) autoCmds.add(it) else oneTaps.add(it) }
    }.snapshot()

    @Test
    fun onlyEnabledAutomaticCommandsContributeHooks() {
        val enabled = command(1, enabled = true)
        val disabled = command(2, enabled = false)
        val oneTap = command(3, enabled = true, auto = false)
        val hooks = MethodHookConfigPublisher.collect(snapshot(enabled, disabled, oneTap))
        assertEquals(1, hooks.size)
        assertEquals(AppMethodCall.hookId(1, 101), hooks.single().id)
        assertEquals("com.example.target", hooks.single().packageName)
        assertEquals(listOf("java.lang.String"), hooks.single().paramTypes)
    }

    @Test
    fun disablingOrDeletingTheCommandRemovesTheHookOnTheNextSnapshot() {
        assertEquals(1, MethodHookConfigPublisher.collect(snapshot(command(1, true))).size)
        assertEquals(0, MethodHookConfigPublisher.collect(snapshot(command(1, false))).size)
        assertEquals(0, MethodHookConfigPublisher.collect(snapshot()).size)
    }

    @Test
    fun nonMethodTriggersAreIgnored() {
        val command = command(
            1,
            enabled = true,
            triggers = listOf(CommandItem(9, "亮屏", "", Ic.Bolt, "trigger.screen_on")),
        )
        assertTrue(MethodHookConfigPublisher.collect(snapshot(command)).isEmpty())
    }
}
