# 输出变量规范

本文是 FlowGlass **输出变量**的权威说明。触发器和动作的上下文值不再作为全局内置变量，而是
组件的**实例级输出**：组件元数据声明能产出什么，规则实例决定叫什么名字。

变量的通用语法（`{名字}`、作用域前缀、转义）、类型转换、各调用点行为与生命周期见
[`variables.md`](variables.md)；本文专注输出变量的元数据、命名和运行期发布规则。

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

`OutputSpec` 只存在于组件元数据，不写进规则文件。每个组件声明自己的专属输出；`OutputCatalog`
不再派生共享的触发器/动作通用三项：

- 有专属声明的组件：只使用 `meta.outputs`。
- 没有专属声明的触发器：生成一个组件专属主输出，key 为 `<typeId>.primary`，默认名为
  「组件名 + 触发时间」，值为本次触发时间（HH:mm）。
- 没有专属声明的动作和流程块：生成一个组件专属主输出，key 为 `<typeId>.primary`，默认名为
  「组件名 + 结果」，值为动作/流程块的结果摘要。
- 条件：没有输出。

因此每个触发器、动作、流程块都至少有一个自己的输出，但不同组件不会再共用
`触发时间`、`动作状态`、`动作结果`、`动作名称` 这些名字。一个组件可以声明多个专属输出，
例如通知触发器有标题/正文/应用，Shell 有 `Shell标准输出`/`Shell标准错误`/`Shell退出码`。

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
- 每个触发器/动作/流程块至少启用一个绑定；默认绑定全部启用。
- 条件不保存输出绑定。

旧规则没有绑定时，运行期按元数据默认名补全；保存时会规范化并写回。旧版的共享通用绑定
（`trigger.firedAt` / `action.status` 等）已经不在 schema 里，会被 `normalizeRule` 丢弃，
不再作为别名保留。

## 4. 命名与冲突

有专属输出的组件使用声明里的默认名；没有专属输出的组件使用组件名派生的主输出名，例如
「振动结果」「Toast提示结果」「启动应用结果」「开机完成触发时间」。已有专属绑定保留原名和
启用状态，不会因为升级自动改名；输出页的「恢复默认」使用当前 schema 的默认名。

`OutputCatalog.allocateName` 先尝试默认名，冲突时追加 `2`、`3`……；保存和加载时
`normalizeRule` 会：

1. 丢弃未知 key（包括已移除的共享通用 key）；
2. 为新增的元数据输出补默认绑定；
3. 把与内置、局部、全局、方法提取名或其他输出冲突的名字改成唯一名；
4. 清空条件上的绑定。

输出名在运行期只读。「设置变量」和脚本 `set()` 遇到输出名会失败，提示「输出变量只读」。

## 5. 运行期语义

### 5.1 触发器

`TriggerDispatcher` 在匹配后构造一次触发器上下文：

- 有专属输出时，从事件读取电量、WiFi、通知、应用、位置、耳机等值；
- 没有专属输出时，写入该组件的唯一主输出（默认名为「组件名触发时间」）；
- 方法调用触发器还包含固定字段和用户提取字段。

条件和后续动作使用同一份上下文。队列模式保留触发时的快照。触发器输出名会作为
`readOnlyNames` 传给执行器，脚本不能覆盖。

### 5.2 动作

`ActionOutcome.outputs` 以稳定 key 返回值。执行器写入：

- 专属：`shell.stdout`、`shell.stderr`、`shell.code`、`http.body`、`http.status`、
  `script.result` 等；
- 没有专属输出的动作/流程块：写入该组件的唯一主输出，值为执行结果摘要。

只写当前实例启用的绑定。动作失败也会写自己的主输出（如果该组件没有专属输出）；有专属输出
的动作只写专属值，不再写共享状态/结果/名称。`RETRY` 只保留最终一次尝试的结果。流程块执行
完成后写自己的主输出。

### 5.3 类型

按 `OutputSpec.type` 转换：整数接受 Int/Long/数字字符串；真假接受 Boolean/"true"/"false"；
文本使用 `VariableValues.text()`；列表沿用现有转换规则。类型不符时跳过该输出，不把动作判失败。

### 5.4 解析顺序

保存期禁止同名，正常规则无歧义。运行时兜底顺序为：

`local → global → builtin → output → run`

参数里统一写 `{名字}`。需要显式指定作用域时写 `{rule.名字}`、`{global.名字}`、`{run.名字}`、
`{event.名字}`：前缀只认这四个，其余带点的写法不算变量、保持字面量；名字部分允许中文，和裸名
一样按 `VariableName` 校验。旧写法 `%{...}` 使用同一套规则（包括中文与作用域前缀），老文件里
的 `%{global.name}` 继续可用。

### 5.5 空值与未设置

输出值是**已设置但为空**，和**没有这个变量**是两种状态：

- 通知标题/正文、WiFi 的 SSID、蓝牙与耳机的名称在系统事件里可能合法地为空串。这种输出照常写入值表、
  照常进入只读名称表，`set()` 为真、`empty()` 为真、`unset()` 为假。
- 只有事件里根本没有该字段时，才不写这个键：`set()` 为假、`empty()` 为假、`unset()` 为真。
- 因此判断「取到了名字」请用 `empty()` 取反，不要用 `unset()`；旧规则里用 `unset()` 判断名称的，
  在这些事件上会从匹配变为不匹配。

自动指令的顶层条件在规则执行器之外求值，它的取值表是「持久变量（局部遮蔽同名全局）+ 本次事件输出」，
同名的持久变量会被事件输出盖过。规则体内部仍按 §5.4 的顺序解析裸名（`local → global → builtin →
output → run`），同名的局部或全局变量反而会盖过事件输出。保存期禁止同名，所以正常规则两者一致；
只有旧规则或手工改过的规则才可能出现这种差异，排错时以本节为准。

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

其余触发器（时间、亮屏/熄屏、充电、开机、用户在场等）没有专属声明，各自生成一个主输出
`<typeId>.primary`，默认名分别为「定时触发时间」「亮屏触发时间」「开机完成触发时间」等。
这些主输出彼此独立，不再共用 `触发时间`。

### 6.2 动作专属输出

| 动作 | 专属 key |
| --- | --- |
| `action.root_shell` | `shell.stdout`、`shell.stderr`、`shell.code` |
| `action.script` | `script.result` |
| `action.http_request` | `http.body`、`http.status` |

其余普通动作和 7 个流程块没有专属声明，各自生成一个主输出 `<typeId>.primary`，默认名为
「动作名 + 结果」，例如「Toast提示结果」「振动结果」「启动应用结果」「如果结果」。

### 6.3 方法调用提取项

提取项是动态输出，不属于固定 schema。输出页只读展示，编辑入口仍然是「配置提取数据」。
运行时它们和固定输出一样进入只读名单。

## 7. 编辑器入口

- 文本输入框右侧有两个图标：`{}` 插入变量，`→` 配置输出变量。
- 条件字段只显示 `{}`。
- 插入变量按当前编辑位置过滤：根条件只列触发器输出；块内条件列触发器和该块之前动作的输出；动作列触发器和此前动作的输出。后续动作的输出不会出现在列表里，触发器参数也不列组件输出，触发器编辑页不提供变量插入按钮。
- 动作和触发器列表每一行行尾、删除按钮左边有花括号 `{}` 入口（`output-item-<id>`，无障碍名「配置输出变量<名称>」），点开后是整页输出变量编辑，保存直接写回该动作/触发器。顶层动作和块内子动作都用这一处入口；条件没有输出，因此没有入口。删除按钮是手绘的 × 图标（不是字符）。
- 动作参数页顶栏不再放 `⇥`，避免同一动作出现两个入口；参数输入框右侧的图标仍然保留。
- 流程块页顶栏同样有 `⇥`（块自身的输出），块内动作在块页的动作行上行内配置。
- 输出页不提供启用/禁用：有这个组件就有这个输出，保存时统一按启用写回。
- 输出页支持改名、恢复默认名；保存只改当前块。界面不再提供「添加别名」：每个输出只保留一个名字，直接在输入框里改即可；历史数据里同一 key 的多余绑定会标成「别名」，可用「移除别名」清掉。

## 8. 持久化与校验

- `RuleJson.CURRENT_FORMAT_VERSION = 6`。
- v5 文件按「没有输出绑定」加载，由 `normalizeRule` 生成默认绑定；不写旧变量别名。
- 旧版共享通用 key 不再属于 schema，加载/保存时被丢弃；没有专属输出的组件会补一个主输出。
- `RuleComponent.outputs`、`CommandItem.outputs`、`TriggerComponent.outputs` 随规则/触发器传递。
- `ParameterCodec.validateDocument` 拒绝未知 key、非法名、重名和全部禁用。
- `RuleValidator` 额外检查跨组件重名，以及与内置、局部、全局、方法提取名的冲突。

## 9. 扩展清单

新增触发器或动作时：

1. 在 `ComponentMeta.outputs` 声明专属 `OutputSpec`。有自然数据就声明完整字段，不要用共享通用 key。
2. 如果组件没有自然数据，可以只声明参数，`OutputCatalog` 会生成一个 `<typeId>.primary` 主输出。
3. 给 `OutputCatalog.allOutputs` 的 key 保持稳定，不改已发布 key。
4. 运行时在对应位置返回专属 `outputs` map；主输出由执行器写结果摘要。
5. 补 engine 测试：schema 非空、key 唯一、默认名合法、运行期可见性。
6. 补 UI 测试：输出页可打开、至少一个启用、条件无入口。
7. 更新本文矩阵和 `docs/component-output-checklist.md`。
