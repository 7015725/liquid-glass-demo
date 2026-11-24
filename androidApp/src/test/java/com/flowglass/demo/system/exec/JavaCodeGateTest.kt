package com.flowglass.demo.system.exec

import android.content.Context
import com.flowglass.demo.ui.Cmd
import com.flowglass.demo.ui.CommandItem
import com.flowglass.demo.ui.Ic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JavaCodeGateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun importedCodeIsPendingUntilConfirmed() {
        val code = "return 41;"
        JavaCodeGate.markPending(context, code)
        assertTrue(JavaCodeGate.isPending(context, code))
        JavaCodeGate.confirm(context, code)
        assertFalse(JavaCodeGate.isPending(context, code))
    }

    @Test
    fun markImportedWalksNestedActions() {
        val code = "return 42;"
        val item = CommandItem(
            1, "执行 Java 代码", """{"code":"$code","timeout":30}""", Ic.Bolt, "action.java_code",
        )
        val command = Cmd(1, false, Ic.Bolt, "导入", "", actions = listOf(item))
        JavaCodeGate.markImported(context, listOf(command))
        assertTrue(JavaCodeGate.isPending(context, code))
        JavaCodeGate.confirm(context, code)
    }
}
