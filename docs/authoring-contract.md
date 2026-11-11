# 编写器接口契约（本次集成固定）

## 交付边界
用户最新要求是根据技术方案实现自动化任务的**编写**，不是这次完成全部特权执行端。覆盖自动/一键指令真实 CRUD、元数据表单、触发器/条件匹配、动作排序与嵌套、变量、版本化 JSON 导入预览确认/导出。不得把保存当作已执行，不请求 Root，不自动启用导入内容。技术方案第5/6/10节是功能依据；库版本须实际验证。

## engine JVM 模块 — `com.flowglass.engine`
以下名称和参数是 UI/持久化共同契约，所有模型为 Kotlin data class，默认值与 Serializable 支持必须有；勿随意改命名，扩展可新增。

```kotlin
@Serializable data class Rule(
 val id: String = UUID.randomUUID().toString(), val name: String = "",
 val description: String = "", val type: String = "auto", val enabled: Boolean = false,
 val runMode: String = "drop", val triggerMatch: MatchSpec = MatchSpec(),
 val triggers: List<Component> = emptyList(), val conditionMatch: MatchSpec = MatchSpec(),
 val conditions: List<Component> = emptyList(), val locals: List<Variable> = emptyList(),
 val actions: List<Component> = emptyList(), val pinned: Boolean = false, val schemaVersion: Int = 1,
 val debounceMillis: Long = 0
)
@Serializable data class Component(
 val id: String = UUID.randomUUID().toString(), val type: String,
 val params: Map<String, JsonElement> = emptyMap(), val timeoutSeconds: Int = 30,
 val onError: String = "stop", val retryCount: Int = 0,
 val children: List<Component> = emptyList(), val elseActions: List<Component> = emptyList()
)
@Serializable data class MatchSpec(val mode: String = "any", val windowSeconds: Int = 60,
 val atLeast: Int? = null, val tree: MatchNode? = null)
@Serializable data class MatchNode(val op: String = "leaf", val ref: String? = null,
 val children: List<MatchNode> = emptyList())
@Serializable data class Variable(val name: String, val type: String = "string",
 val initial: JsonElement = JsonPrimitive(""), val resetEachRun: Boolean = false)
enum class Kind { TRIGGER, CONDITION, ACTION }
data class ParamSpec(val key: String, val label: String, val kind: String = "text",
 val required: Boolean = true, val defaultValue: JsonElement = JsonPrimitive(""),
 val choices: List<String> = emptyList(), val hint: String = "")
data class ComponentMeta(val typeId: String, val name: String, val category: String,
 val kind: Kind, val params: List<ParamSpec> = emptyList(), val permissions: List<String> = emptyList(),
 val minTier: String = "NORMAL", val outputs: List<String> = emptyList(), val description: String = "")
data class ValidationIssue(val path: String, val message: String)
```
- ParamSpec.kind：text / long / double / bool / choice / json / code / time。Choice 值直接作中文或协议值，UI 不猜编码。
- Variable.type：string / int / decimal / bool / list / dict / null；整数 Long，不经 Double 中转。
- MatchNode.op：and / or / not / leaf；ref 指 Component.id，非列表下标，排序不得破坏引用；not 恰好一个子节点。mode custom 的 atLeast 与 tree 互斥。
- 动作流程：action.if 的 params.expression 是 JS 条件字符串，children 为 then，elseActions 为 else；action.loop 的 params.count 为正整数，children 为循环体。
- 顶层/嵌套组件数量、深度、文件大小必须有限制。未知版本/组件不静默丢失，报错。删除被匹配树引用的组件需更新树或显示校验错误。输入非法必须保留供修正，不能 toIntOrNull ?: default 静默修复。

```kotlin
object ComponentRegistry {
 val items: List<ComponentMeta>
 fun find(typeId: String): ComponentMeta?
 fun newComponent(typeId: String): Component
}
object RuleValidator {
 fun validate(rule: Rule): List<ValidationIssue>
 fun validateVariable(variable: Variable): List<ValidationIssue>
}
@Serializable data class RuleBundle(val formatVersion: Int = 1, val rules: List<Rule>, val globals: List<Variable> = emptyList())
object RuleCodec {
 val json: Json
 fun encodeRule(rule: Rule): String
 fun decodeRule(text: String): Rule // 内部存储，不改 ID/enabled；不做可执行性校验，允许编辑草稿
 fun exportBundle(rules: List<Rule>, globals: List<Variable> = emptyList()): String
 fun decodeBundle(text: String): RuleBundle // 校验格式/内容；兼容文档单规则、bundle；限制1MiB；不重新生成ID
 fun prepareImport(bundle: RuleBundle): RuleBundle // 所有rules分配新ID、enabled=false，内部call-rule引用重新映射；组件ID可保持（每条规则独立）
 fun scriptWarnings(bundle: RuleBundle): List<String> // 返回所有深层 Shell/JS/表达式完整文本，不截断；脚本清单用于二次确认
}
```
必需元数据包含技术文档示例6种 typeId：trigger.wifi_connected / trigger.bluetooth_connected / condition.time_range / condition.variable / action.set_ringer / action.list_append / action.shell（Shell cmd + root bool + backend choice 可扩展）。以及通知/时间/充电/屏幕/电量/应用/全局变量变化触发；WiFi/充电/电量/表达式条件；通知/打开应用/延时/JS/变量赋值删除/列表操作/字典操作/if/loop/stop/call_manual/wait_event 动作。特殊能力只标注需要的权限，未实现执行端不伪装可运行。

## 持久化 — `com.flowglass.demo.data`
在 androidApp 内 Room（规则、局部变量复合键 ruleId+name、独立全局变量表）。从空库开始，无假规则/日志；样例由用户显式选择创建且标为停用。

```kotlin
class RuleRepository(context: Context) {
 val rules: StateFlow<List<Rule>>
 val globals: StateFlow<List<Variable>>
 val error: StateFlow<String?>
 suspend fun refresh()
 suspend fun save(rule: Rule) // 校验；失败抛可展示异常；IO线程
 suspend fun delete(id: String)
 suspend fun saveGlobal(variable: Variable)
 suspend fun deleteGlobal(name: String)
 suspend fun importBundle(bundle: RuleBundle) // 整批原子；已由prepareImport处理；全局重名拒绝整个导入以免覆盖
}
```
所有更新事务后再通知 stateflow。局部变量真实独立表，与规则JSON一致，删除关联级联。不得 swallow 保存失败。初次 refresh 由调用者发起。Room KSP 与依赖由构建负责人配置，建议 Room 2.8.4 / 实际存在兼容 KSP，其他版本以实际工具结果为准。

## 编辑器 — `com.flowglass.demo.ui.editor`
新增文件，不改 App.kt/Model.kt/CommandList.kt/HomeScreen.kt（由集成人修改）：
```kotlin
@Composable fun RuleEditorScreen(
 initialRule: Rule, globals: List<Variable>, onSave: (Rule) -> Unit, onCancel: () -> Unit
)
@Composable fun GlobalVariablesScreen(
 globals: List<Variable>, onSave: (Variable) -> Unit, onDelete: (String) -> Unit, onBack: () -> Unit
)
```
全屏，自己处理 systemBars/imePadding；以项目 C/T/PressCard/玻璃组件延续视觉。编辑草稿须在旋转后保留（rememberSaveable + RuleCodec），退出有未保存确认，校验失败显示路径和中文提示；按保存交给父级异步持久化，父级成功再关闭（失败消息父级显示）。不要点击保存立即自行关闭。
- 名称/描述；自动/一键不可误混；重复策略 drop/queue/restart/parallel；防抖毫秒。
- 触发器、条件、动作页签或分段，匹配 any/all/custom，事件窗口；custom 提供至少N和可视化与/或/非嵌套树（可以树弹层操作，非必须手写JSON）。
- 搜索/分类选组件→根据params表单→保存、再编辑、复制、删除、上下移动。
- 动作超时/失败策略/重试次数及嵌套if/loop，提供内部输出说明/变量引用帮助。
- 7类变量、initial JSON校验、局部resetEachRun；全局通过独立页面管理，内部变量只读说明。
- 实际组件语义contentDescription/testTag，点击至少48dp；不依赖指针手势代替无障碍语义。

## 测试与构建职责
- engine负责人只写 engine/（包括自己的build.gradle.kts），循环严格 RED/GREEN，日志 outputs/.../engine-*。
- UI负责人只写 ui/editor/ 和对应 ui/editor 测试；可新增 test 依赖需求交给构建负责人。
- 数据负责人只写 data/ 和对应 data 测试；实际Room/Robolectric落盘测试优先。
- 构建负责人统一 Gradle 配置、依赖/SDK发现、初始构建，不修改业务。
- 父级集成现有四页、数据状态、JSON SAF导入/导出、真实概览，移除假执行。
