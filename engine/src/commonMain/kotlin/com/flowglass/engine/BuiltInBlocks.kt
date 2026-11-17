package com.flowglass.engine

import kotlinx.serialization.json.JsonPrimitive

/**
 * Task-chain blocks and system actions.
 *
 * They are ordinary ACTION components so the existing add panel, parameter editor, tier badge
 * and permission hint keep working unchanged. Execution does not go through [Action] handlers:
 * [RuleExecutor] dispatches them by [ComponentMeta.block], because blocks need the run budget,
 * the nesting depth and the variable scopes.
 */
object BuiltInBlocks {
    private val normalLayer = listOf(LayerSupport(ExecutionLayer.NORMAL))

    private fun duration(key: String, label: String, millis: Long, required: Boolean = false, hint: String = "") =
        ParamSpec(key, label, ParamType.DURATION, required, JsonPrimitive(millis), hint = hint)

    val metadata: List<ComponentMeta> = listOf(
        ComponentMeta(
            "block.if", "如果", "流程控制", ComponentKind.ACTION,
            description = "条件成立时执行「就」里的步骤，否则执行「否则」；旧数据会等价转换。",
            supports = normalLayer,
            block = BlockKind.IF,
        ),
        ComponentMeta(
            "block.loop", "循环", "流程控制", ComponentKind.ACTION,
            listOf(
                ParamSpec("mode", "方式", ParamType.ENUM, false, JsonPrimitive("times"), options = listOf(
                    ParamOption("times", "按次数"), ParamOption("while", "条件成立时一直循环"),
                )),
                ParamSpec("times", "次数", ParamType.INTEGER, false, JsonPrimitive(3), minValue = 1, maxValue = 1000),
            ),
            description = "按次数循环，或条件成立时一直循环；最多 1000 次。",
            supports = normalLayer,
            block = BlockKind.LOOP,
        ),
        ComponentMeta(
            "block.wait_until", "等待直到", "流程控制", ComponentKind.ACTION,
            listOf(
                duration("timeout", "超时", 30_000, required = true),
                duration("poll", "轮询间隔", 1_000, hint = "250 毫秒–5 秒；整个等待只计 1 步。"),
            ),
            description = "等待条件成立；超时后按失败处理。",
            supports = normalLayer,
            block = BlockKind.WAIT,
        ),
        ComponentMeta(
            "block.break", "跳出循环", "流程控制", ComponentKind.ACTION,
            description = "结束最近的一层循环，只能放在循环内。",
            supports = normalLayer,
            block = BlockKind.BREAK,
        ),
        ComponentMeta(
            "block.stop", "停止任务", "流程控制", ComponentKind.ACTION,
            listOf(
                ParamSpec("status", "结果", ParamType.ENUM, false, JsonPrimitive("success"), options = listOf(
                    ParamOption("success", "按成功结束"), ParamOption("failure", "按失败结束"),
                )),
                ParamSpec("message", "说明", ParamType.TEXT, false, JsonPrimitive(""), hint = "留空使用默认说明。"),
            ),
            description = "提前结束整条任务，可按成功或失败定型。",
            supports = normalLayer,
            block = BlockKind.STOP,
        ),
        ComponentMeta(
            "action.set_var", "设置变量", "流程控制", ComponentKind.ACTION,
            listOf(
                ParamSpec("name", "变量名", ParamType.TEXT, true, JsonPrimitive(""),
                    hint = "只填名字，例如 城市；新变量会自动创建。"),
                ParamSpec("type", "类型", ParamType.ENUM, false, JsonPrimitive("text"), options = listOf(
                    ParamOption("text", "文本"), ParamOption("number", "数字"),
                    ParamOption("boolean", "真假"), ParamOption("list", "列表"),
                )),
                ParamSpec("value", "内容", ParamType.TEXT, false, JsonPrimitive(""),
                    hint = "支持 {变量}。"),
                ParamSpec("op", "运算", ParamType.ENUM, false, JsonPrimitive("set"), options = listOf(
                    ParamOption("set", "设为"), ParamOption("add", "加"), ParamOption("sub", "减"),
                    ParamOption("mul", "乘"), ParamOption("div", "除"),
                )),
                ParamSpec("scope", "保存范围", ParamType.ENUM, false, JsonPrimitive("local"), options = listOf(
                    ParamOption("local", "本指令"), ParamOption("global", "所有指令"),
                )),
                ParamSpec("retain", "运行后保留", ParamType.ENUM, false, JsonPrimitive("false"), options = listOf(
                    ParamOption("false", "关闭"), ParamOption("true", "开启"),
                )),
            ),
            description = "赋值或做简单运算；新建变量时可选择保存范围，局部变量可选择运行后是否保留。",
            supports = normalLayer,
            block = BlockKind.SET_VAR,
        ),
        ComponentMeta(
            "action.run_rule", "执行另一条指令", "流程控制", ComponentKind.ACTION,
            listOf(
                ParamSpec("rule", "目标指令", ParamType.TEXT, true, JsonPrimitive(""),
                    hint = "填写目标指令的名称；保存时会检查调用成环。",
                    editor = ParamEditor.RULE, source = ParamSource.RULES),
                ParamSpec("wait", "等待", ParamType.ENUM, false, JsonPrimitive("true"), options = listOf(
                    ParamOption("true", "等它执行完再继续"), ParamOption("false", "发起后立即继续"),
                )),
            ),
            description = "执行另一条指令的动作；可选择等待或立即继续。",
            supports = normalLayer,
            block = BlockKind.RUN_RULE,
        ),
    )
}
