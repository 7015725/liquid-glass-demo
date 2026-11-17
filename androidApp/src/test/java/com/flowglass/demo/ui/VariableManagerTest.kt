package com.flowglass.demo.ui

import com.flowglass.demo.xposed.method.MethodHookCatalog
import com.flowglass.demo.xposed.method.MethodHookStore
import com.flowglass.engine.VariableScope
import com.flowglass.engine.methodhook.MethodExtraction
import com.flowglass.engine.methodhook.MethodExtractionSource
import com.flowglass.engine.methodhook.MethodHookSpec
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VariableManagerTest {
    private fun stateWithAction(parameter: String): AppState {
        val state = AppState()
        state.autoCmds.add(
            Cmd(
                id = 1,
                auto = false,
                ic = Ic.Bolt,
                title = "测试指令",
                desc = "",
                actions = listOf(CommandItem(1, "通知", parameter, Ic.Bell, "action.toast")),
            ),
        )
        return state
    }

    @Test
    fun editorPickerOffersChineseBuiltinsAndNoRetiredNames() {
        val state = stateWithAction("")
        val options = VariableCatalog.forEditor(state, 1)
        assertTrue(options.any { it.name == "通知标题" })
        assertTrue(options.any { it.name == "上一步结果" })
        assertTrue(options.none { it.name == "shell.stderr" || it.name == "http.status" || it.name == "run.value" })
        assertEquals("{通知标题}", VariableCatalog.token("通知标题"))
    }

    @Test
    fun builtinNameCannotBeReused() {
        val state = stateWithAction("")
        assertTrue(VariableCatalog.validateName(state, "通知标题", null, VariableScope.GLOBAL).orEmpty().contains("已被占用"))
    }

    @Test
    fun methodHookExtractionsAreBuiltinsAndCannotBeReused() {
        val app = RuntimeEnvironment.getApplication()
        val store = MethodHookStore(File(app.filesDir, "method-hooks.json"))
        store.load()
        store.save(
            listOf(
                MethodHookSpec(
                    id = "hook-1",
                    packageName = "com.example.target",
                    className = "com.example.Target",
                    methodName = "submit",
                    extractions = listOf(
                        MethodExtraction("订单号", MethodExtractionSource.ARGUMENT, 0),
                        MethodExtraction("金额", MethodExtractionSource.RESULT),
                    ),
                ),
            ),
        )
        MethodHookCatalog.refresh(app)
        try {
            val state = stateWithAction("")
            val options = VariableCatalog.forManager(state)
            val order = options.single { it.name == "订单号" }
            assertEquals(VariableScope.BUILTIN, order.scope)
            assertTrue(order.note.contains("方法钩子"))
            assertTrue(VariableCatalog.validateName(state, "订单号", null, VariableScope.GLOBAL)
                .orEmpty().contains("方法钩子提供的内置变量"))
            assertTrue(VariableCatalog.validateName(state, "钩子应用", null, VariableScope.GLOBAL)
                .orEmpty().contains("内置变量"))
        } finally {
            val file = File(app.filesDir, "method-hooks.json")
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
            MethodHookCatalog.refresh(app)
        }
    }

    @Test
    fun renamingAGlobalUpdatesAllReferencesAndItsValueKey() {
        val state = stateWithAction("去{城市}，旧写法 %{global.城市}")
        state.engineGlobals = mapOf("城市" to JsonPrimitive("北京"))
        state.engineGlobalMeta = mapOf("城市" to com.flowglass.engine.GlobalVariableMeta(note = "目的地"))

        state.renameVariable(null, "城市", "地点", VariableScope.GLOBAL)

        assertEquals("去{地点}，旧写法 %{global.地点}", state.autoCmds.single().actions.single().parameter)
        assertEquals(setOf("地点"), state.engineGlobals.keys)
        assertEquals("目的地", state.engineGlobalMeta.getValue("地点").note)
    }

    @Test
    fun referenceCountCoversBothSyntaxes() {
        val state = stateWithAction("{城市} %{global.城市}")
        state.engineGlobals = mapOf("城市" to JsonPrimitive("北京"))
        assertEquals(2, VariableCatalog.references(state, "城市", VariableScope.GLOBAL, null))
    }

    @Test
    fun legacyNameIsRejectedByTheNewNameRule() {
        val state = stateWithAction("")
        assertTrue(VariableCatalog.validateName(state, "my.var", null, VariableScope.GLOBAL).orEmpty().contains("不能有空格和点"))
    }
}
