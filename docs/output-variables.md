# 输出变量规范

本文是 FlowGlass 运行上下文变量的唯一权威说明。触发器和动作的上下文值不再作为全局内置
变量，而是组件的**实例级输出**：组件元数据声明能产出什么，规则实例决定叫什么名字。

## 1. 五个变量域

| 域 | 说明 | 生命周期 | 可写 |
| --- | --- | --- | --- |
| `builtin` | 真正的系统变量，例如「上一步结果」 | 单次运行 | 否 |
| `output` | 触发器/动作实例输出 | 单次运行；触发器在条件前、动作完成后可见 | 否 |
| `local` | 指令局部变量 | 按 `resetEachRun` | 是 |
| `global` | 全局变量 | 跨规则 | 是 |
| `event` | 原始事件键，仅供内部兼容 | 单次运行 | 否 |

用户界面只展示系统变量、输出变量、局部变量、全局变量。参数里统一写 `{名字}`。

## 2. 组件元数据：OutputSpec

```kotlin
@Serializable
data class OutputSpec(
    val key: String,          // 稳定内部键，例如 "shell.stdout"
    val label: String,        // 中文标签
    val type: VariableType,   // INT / BOOLEAN / TEXT / *_LIST
    val defaultName: String,  // 默认引用名
    val description: String = "",
)
```

`OutputSpec` 只存在于组件元数据，不写进规则文件。组件作者只声明专属输出；`OutputCatalog`
按组件种类派生通用输出：

- 触发器：`trigger.firedAt`、`trigger.reason`、`trigger.name`。
- 动作和流程块：`action.status`、`action.result`、`action.name`。
- 条件：没有输出。

因此新增一个动作时，即使只声明参数，它也自动获得三个通用动作变量。

## 3. 规则实例：OutputBinding

```kotlin
@Serializable
data class OutputBinding(
    val key: String,
    val name: String,
    val enabled: Boolean = true,
)
```

- `key` 必须属于组件的完整输出 schema（`OutputCatalog.allOutputs`）。
- `name` 按 `VariableName` 校验：中文/字母/数字/`_`/`-`，1–64，不含空格和点。
- 同一 `key` 可以有多个别名，但同一规则内所有启用名必须唯一。
- 每个触发器/动作至少启用一个绑定。
- 条件不保存输出绑定。

旧规则没有绑定时，运行期按元数据默认名补全；保存时会规范化并写回。

## 4. 命名与冲突

`OutputCatalog.allocateName` 先尝试 `defaultName`，冲突时追加 `2`、`3`……；保存和加载时
`normalizeRule` 会：

1. 丢弃未知 key；
2. 为新增的元数据输出补默认绑定；
3. 把与内置、局部、全局、方法提取名或其他输出冲突的名字改成唯一名；
4. 清空条件上的绑定。

输出名在运行期只读。「设置变量」和脚本 `set()` 遇到输出名会失败，提示「输出变量只读」。

## 5. 运行期语义

### 5.1 触发器

`TriggerDispatcher` 在匹配后构造一次触发器上下文：

- 通用输出来自触发时间、触发原因、触发器名称；
- 专属输出来自事件（电量、WiFi、通知、应用、位置、耳机等）；
- 方法调用触发器还包含固定字段和用户提取字段。

条件和后续动作使用同一份上下文。队列模式保留触发时的快照。触发器输出名会作为
`readOnlyNames` 传给执行器，脚本不能覆盖。

### 5.2 动作

`ActionOutcome.outputs` 以稳定 key 返回值。执行器写入：

- 通用：`action.status`、`action.result`、`action.name`；
- 具体：`shell.stdout`、`shell.stderr`、`shell.code`、`http.body`、`http.status`、
  `script.result` 等。

只写当前实例启用的绑定；动作失败也写通用状态/结果，缺省的具体输出不写。`RETRY` 只保留
最终一次尝试的结果。流程块执行完成后同样写通用输出。

### 5.3 类型

按 `OutputSpec.type` 转换：整数接受 Int/Long/数字字符串；真假接受 Boolean/"true"/"false"；
文本使用 `VariableValues.text()`；列表沿用现有转换规则。类型不符时跳过该输出，不把动作判失败。

### 5.4 解析顺序

保存期禁止同名，正常规则无歧义。运行时兜底顺序为：

`local → global → builtin → output → run`

`event.`、`run.`、`rule.`、`global.` 前缀仍被解析器识别，只用于旧数据和内部兼容，不作为
用户界面入口。

## 6. 输出矩阵

### 6.1 触发器专属输出

| 触发器 | 专属 key |
| --- | --- |
| `trigger.battery_level`、`trigger.battery_low` | `trigger.battery.level` |
| `trigger.wifi` | `trigger.wifi.ssid`、`trigger.wifi.state` |
| `trigger.bluetooth` | `trigger.bluetooth.name`、`trigger.bluetooth.state` |
| `trigger.notification` | `trigger.notification.app`、`.user`、`.title`、`.text` |
| `trigger.app_foreground`、`trigger.app_process` | `trigger.app.package`、`trigger.app.user` |
| `trigger.app_crash`、`trigger.media` | 应用两项 + `trigger.app.event` |
| `trigger.foreground_service` | `trigger.app.package`、`trigger.app.user` |
| `trigger.keyguard` | `trigger.keyguard.state` |
| `trigger.headset` | `trigger.headset.name`、`.kind`、`.state` |
| `trigger.location_enter`、`trigger.location_exit` | `trigger.location.lat`、`.lng` |
| `trigger.app_method_call` | `trigger.method.app`、`.user`、`.process`、`.method`、`.signature`、`.hookId` |

其余触发器（时间、亮屏/熄屏、充电、开机、用户在场等）只有通用三项。

### 6.2 动作专属输出

| 动作 | 专属 key |
| --- | --- |
| `action.root_shell` | `shell.stdout`、`shell.stderr`、`shell.code` |
| `action.script` | `script.result` |
| `action.http_request` | `http.body`、`http.status` |

其余普通动作和 7 个流程块只有通用三项。

### 6.3 方法调用提取项

提取项是动态输出，不属于固定 schema。输出页只读展示，编辑入口仍然是「配置提取数据」。
运行时它们和固定输出一样进入只读名单。

## 7. 编辑器入口

- 文本输入框右侧有两个图标：`{}` 插入变量，`⇥` 配置输出变量。
- 条件字段只显示 `{}`。
- 组件编辑页顶栏有 `⇥` 兜底入口，无文本参数的组件也能进入。
- 流程块页顶栏同样有 `⇥`。
- 输出页支持改名、启用/禁用、添加别名、恢复默认名；保存只改当前草稿或当前块。

## 8. 持久化与校验

- `RuleJson.CURRENT_FORMAT_VERSION = 6`。
- v5 文件按「没有输出绑定」加载，由 `normalizeRule` 生成默认绑定；不写旧变量别名。
- `RuleComponent.outputs`、`CommandItem.outputs`、`TriggerComponent.outputs` 随规则/触发器传递。
- `ParameterCodec.validateDocument` 拒绝未知 key、非法名、重名和全部禁用。
- `RuleValidator` 额外检查跨组件重名，以及与内置、局部、全局、方法提取名的冲突。

## 9. 扩展清单

新增触发器或动作时：

1. 在 `ComponentMeta.outputs` 声明专属 `OutputSpec`，不要重复通用 key。
2. 给 `OutputCatalog.allOutputs` 的 key 保持稳定，不改已发布 key。
3. 运行时在对应位置返回 `outputs` map。
4. 补 engine 测试：schema 非空、key 唯一、默认名合法、运行期可见性。
5. 补 UI 测试：输出页可打开、至少一个启用、条件无入口。
6. 更新本文矩阵和 `docs/component-output-checklist.md`。
