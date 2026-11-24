package com.flowglass.engine

import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 系统变量（上一步结果）、局部变量和全局变量在动作参数与 Shell 命令里的实际取值。
 *
 * 动作分支断言执行器传给 [Action] 的展开结果；Shell 分支把展开后的命令交给真实 `/bin/sh`
 * 执行，避免只比较字符串而漏掉引号、转义或注入问题。
 */
class VariableCallSitesTest {
    private val registry = BuiltInComponents.registry()

    private class Recorder(private val detail: String = "完成") : Action {
        val params = mutableListOf<Map<String, JsonElement>>()

        override suspend fun run(context: RunContext, component: RuleComponent): ActionOutcome {
            params += component.params
            return ActionOutcome(StepStatus.SUCCESS, detail)
        }
    }

    /** 与 RootSession 相同的契约：整条命令字符串交给 shell 解释。 */
    private class ShellRunner : Action {
        val commands = mutableListOf<String>()
        val outputs = mutableListOf<String>()

        override suspend fun run(context: RunContext, component: RuleComponent): ActionOutcome {
            val command = (component.params["command"] as? JsonPrimitive)?.content.orEmpty()
            commands += command
            val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ActionOutcome(StepStatus.FAILURE, "超时")
            }
            outputs += text
            return ActionOutcome(
                if (process.exitValue() == 0) StepStatus.SUCCESS else StepStatus.FAILURE,
                text.trim().ifEmpty { "exit ${process.exitValue()}" },
            )
        }
    }

    private fun component(typeId: String, vararg params: Pair<String, String>) = RuleComponent(
        typeId,
        params = params.associate { (key, value) -> key to JsonPrimitive(value) },
    )

    private fun rule(vararg actions: RuleComponent, locals: List<LocalVariable> = emptyList()) = Rule(
        id = "rule-call-sites",
        name = "变量调用点",
        type = RuleType.MANUAL,
        enabled = true,
        actions = actions.toList(),
        locals = locals,
    )

    private fun executor(
        actions: Map<String, Action>,
        meta: Map<String, GlobalVariableMeta> = emptyMap(),
    ) = RuleExecutor(
        registry,
        actions,
        CancellationCheckpoint { },
        globalVariableMeta = meta,
    )

    private fun local(name: String, type: String, value: JsonElement) =
        LocalVariable(name, type, value)

    @Test
    fun actionParametersSeeBuiltinLocalAndGlobalValues() = runSuspend {
        val recorder = Recorder("第一步完成")
        val log = executor(
            mapOf(
                "action.copy_clipboard" to Recorder("第一步完成"),
                "action.post_notification" to recorder,
            ),
        ).run(
            rule(
                component("action.copy_clipboard", "text" to "无关"),
                component(
                    "action.post_notification",
                    "title" to "",
                    "content" to "局部={局部}|全局={全局}|上一步={上一步结果}|旧=%{last}",
                ),
                locals = listOf(local("局部", "text", JsonPrimitive("局部值"))),
            ),
            "测试",
            globals = mapOf("全局" to JsonPrimitive("全局值")),
        )

        assertEquals(StepStatus.SUCCESS, log.status, log.detail)
        assertEquals(
            "局部=局部值|全局=全局值|上一步=第一步完成|旧=第一步完成",
            (recorder.params.single()["content"] as JsonPrimitive).content,
        )
    }

    @Test
    fun bareNamePrefersTheLocalAndExplicitPrefixesPickTheScope() = runSuspend {
        val recorder = Recorder()
        val log = executor(mapOf("action.copy_clipboard" to recorder)).run(
            rule(
                component(
                    "action.copy_clipboard",
                    "text" to "裸={shared}|全局=%{global.shared}|局部=%{rule.shared}",
                ),
                locals = listOf(local("shared", "text", JsonPrimitive("局部值"))),
            ),
            "测试",
            globals = mapOf("shared" to JsonPrimitive("全局值")),
        )

        assertEquals(StepStatus.SUCCESS, log.status, log.detail)
        assertEquals(
            "裸=局部值|全局=全局值|局部=局部值",
            (recorder.params.single()["text"] as JsonPrimitive).content,
        )
    }

    @Test
    fun actionParametersRenderTypedValuesAsText() = runSuspend {
        val recorder = Recorder()
        val log = executor(mapOf("action.copy_clipboard" to recorder), meta = typedMeta()).run(
            rule(
                component(
                    "action.copy_clipboard",
                    "text" to "{局部整数}|{全局整数}|{全局开关}|{全局列表}|{全局开关列表}",
                ),
                locals = listOf(local("局部整数", "int", JsonPrimitive(42))),
            ),
            "测试",
            globals = typedGlobals(),
        )

        assertEquals(StepStatus.SUCCESS, log.status, log.detail)
        assertEquals(
            "42|7|true|1,2,3|true,false",
            (recorder.params.single()["text"] as JsonPrimitive).content,
        )
    }

    @Test
    fun unknownNamesStayLiteralInActions() = runSuspend {
        val recorder = Recorder()
        val log = executor(mapOf("action.copy_clipboard" to recorder)).run(
            rule(component("action.copy_clipboard", "text" to "保留{不存在}")),
            "测试",
        )

        assertEquals(StepStatus.SUCCESS, log.status, log.detail)
        assertEquals("保留{不存在}", (recorder.params.single()["text"] as JsonPrimitive).content)
    }

    @Test
    fun shellReceivesBuiltinLocalAndGlobalValues() = runSuspend {
        val shell = ShellRunner()
        val log = executor(
            mapOf("action.copy_clipboard" to Recorder("第一步完成"), "action.root_shell" to shell),
        ).run(
            rule(
                component("action.copy_clipboard", "text" to "无关"),
                component("action.root_shell", "command" to "printf '%s' \"{局部}|{全局}|{上一步结果}\""),
                locals = listOf(local("局部", "text", JsonPrimitive("局部值"))),
            ),
            "测试",
            globals = mapOf("全局" to JsonPrimitive("全局值")),
        )

        assertEquals(StepStatus.SUCCESS, log.status, log.detail)
        assertEquals("局部值|全局值|第一步完成", shell.outputs.single())
    }

    @Test
    fun shellKeepsValuesLiteralInsideSingleAndDoubleQuotes() = runSuspend {
        val dir = Files.createTempDirectory("flowglass-var-shell-")
        val marker = dir.resolve("pwned")
        val dangerous = "a'; touch $marker; echo '\$(touch $marker.sub) `touch $marker.tick` \"q\"\n第二行"
        try {
            val shell = ShellRunner()
            val log = executor(mapOf("action.root_shell" to shell)).run(
                rule(
                    component("action.root_shell", "command" to "printf '%s' \"{危险}\""),
                    component("action.root_shell", "command" to "printf '%s' '{危险}'"),
                ),
                "测试",
                globals = mapOf("危险" to JsonPrimitive(dangerous)),
            )

            assertEquals(StepStatus.SUCCESS, log.status, log.detail)
            assertEquals(listOf(dangerous, dangerous), shell.outputs)
            assertFalse(Files.exists(marker), "变量值不得被 shell 当作命令执行")
            assertFalse(Files.exists(dir.resolve("pwned.sub")))
            assertFalse(Files.exists(dir.resolve("pwned.tick")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun shellAcceptsLegacyPrefixedNamesAndTypedValues() = runSuspend {
        val shell = ShellRunner()
        val log = executor(mapOf("action.root_shell" to shell), meta = typedMeta()).run(
            rule(
                component(
                    "action.root_shell",
                    "command" to "printf '%s' \"{全局整数}|{全局列表}|%{global.sharedValue}|%{rule.localValue}\"",
                ),
                locals = listOf(local("localValue", "text", JsonPrimitive("局部值"))),
            ),
            "测试",
            globals = typedGlobals() + ("sharedValue" to JsonPrimitive("全局值")),
        )

        assertEquals(StepStatus.SUCCESS, log.status, log.detail)
        assertEquals("7|1,2,3|全局值|局部值", shell.outputs.single())
    }

    @Test
    fun shellBareNamePrefersTheLocal() = runSuspend {
        val shell = ShellRunner()
        val log = executor(mapOf("action.root_shell" to shell)).run(
            rule(
                component("action.root_shell", "command" to "printf '%s' \"{shared}|%{global.shared}\""),
                locals = listOf(local("shared", "text", JsonPrimitive("局部值"))),
            ),
            "测试",
            globals = mapOf("shared" to JsonPrimitive("全局值")),
        )

        assertEquals(StepStatus.SUCCESS, log.status, log.detail)
        assertEquals("局部值|全局值", shell.outputs.single())
    }

    @Test
    fun shellKeepsUnknownNamesLiteral() = runSuspend {
        val shell = ShellRunner()
        val log = executor(mapOf("action.root_shell" to shell)).run(
            rule(component("action.root_shell", "command" to "printf '%s' '{不存在}'")),
            "测试",
        )

        assertEquals(StepStatus.SUCCESS, log.status, log.detail)
        assertEquals("{不存在}", shell.outputs.single())
    }

    private fun typedGlobals(): Map<String, JsonElement> = mapOf(
        "全局" to JsonPrimitive("全局值"),
        "全局整数" to JsonPrimitive(7),
        "全局开关" to JsonPrimitive(true),
        "全局列表" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3))),
        "全局开关列表" to JsonArray(listOf(JsonPrimitive(true), JsonPrimitive(false))),
    )

    private fun typedMeta(): Map<String, GlobalVariableMeta> = mapOf(
        "全局" to GlobalVariableMeta("text"),
        "全局整数" to GlobalVariableMeta("int"),
        "全局开关" to GlobalVariableMeta("boolean"),
        "全局列表" to GlobalVariableMeta("int_list"),
        "全局开关列表" to GlobalVariableMeta("boolean_list"),
    )

    private fun runSuspend(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
            result.exceptionOrNull()?.let { failure = it }
        })
        failure?.let { throw it }
    }
}
