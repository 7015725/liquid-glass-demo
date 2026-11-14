# 任务流程设计（FlowGlass Task v1）

交互上参考了 Tasker 等同类自动化应用的使用习惯；执行模型、数据结构和编辑流程都按本应用的
typed 组件模型重新设计，不复刻任何第三方实现。

目标：让「一条指令的任务」能被完整编辑、按顺序执行、步骤之间能传值。

## 1. 术语

| 本应用 | 含义 |
| --- | --- |
| 指令 Command | 触发器 + 条件 + 任务 |
| 任务 Task | **有序步骤数组**（就是 `Cmd.actions`） |
| 步骤 Step | 一个动作组件 + 参数 + 失败策略 |
| 运行上下文 | 一次执行内的变量表 |

## 2. 设计取舍

| 维度 | 本设计 | 原因 |
| --- | --- | --- |
| 控制流 | **只保留顺序执行**，无跳转 | 本应用的任务是「一键执行的一串动作」，块跳转会把编辑器复杂度抬高一个量级 |
| 状态 | `SUCCESS / FAILURE / SKIPPED` 枚举 | 状态机只有前进、失败、跳过三种真实分支 |
| 失败处理 | 步骤级 `onError`：`STOP`（默认）/`CONTINUE`/`RETRY` | 复用已有的 `OnError` 枚举，`RETRY` 只重试一次 |
| 参数 | typed `ParamSpec`（TEXT/INTEGER/PERCENTAGE/DURATION/TRI_STATE/TIME/TIME_RANGE/INT_RANGE/APPLICATION/ENUM） | 编辑器能按类型出控件，执行端不用再解析字符串 |
| 变量 | `%{var}`，在**读取参数时**展开；未定义保持字面量 | 避免编辑期求值导致「改一步、后面全变」 |
| 子任务调用 | **v1 不做** | 需要子任务、返回值、调用链三件套，先保证单任务闭环 |
| 阻塞/等待 | `延时等待` 动作（已有） | 够用 |

## 3. 数据模型

```
Cmd
 └─ actions: List<CommandItem>          // 任务 = 有序步骤
     ├─ id: Long                        // 稳定标识（持久化在 presentation sidecar）
     ├─ typeId: String                  // 组件类型，如 action.http_request
     ├─ parameter: String               // 显示投影（由参数规范值格式化而来）
     ├─ onError: OnError                // STOP / CONTINUE / RETRY
     └─ ic / name                       // 展示用
```

持久化仍然是 v2 的 `RuleComponent(type, params, onError)`：`params` 是唯一事实来源，
`parameter` 只是给人看的文本。编辑器按字段改 `params` 后重新格式化，不反向解析显示文本。

## 4. 执行流程

```
run(task):
  context = { }                        // 变量表，跨步骤共享
  stopped = false
  for step in task.steps:
      if stopped:              record SKIPPED("前序动作失败"); continue

      params = expand(step.params, context)      // %{var} 读取时展开
      outcome = execute(step.typeId, params)

      if outcome.status == FAILURE && step.onError == RETRY:
          outcome = execute(step.typeId, params) // 只重试一次

      record step(outcome, duration)
      context["last"] = outcome.detail           // 上一步结果，便于调试
      meta.outputs.forEach { key -> outcome.output?.let { context[key] = it } }

      if outcome.status == FAILURE && step.onError != CONTINUE:
          stopped = true
```

- 单线程顺序执行，不并发（与现有 `RuleExecutor` 一致）。
- `SKIPPED` 不计失败；整条任务的状态：有失败 → FAILURE，全跳过 → SKIPPED，否则 SUCCESS。
- 取消：沿用 `CancellationCheckpoint`，任何一步前后都可被取消，取消不写终态。

## 5. 参数传递

1. **编辑期**：每个 `ParamSpec` 一个控件，值为规范化的字符串表示（`50%`、`5 秒`、`08:00–22:00`、`工作日`）。
2. **保存期**：字段文本 → `ParameterCodec.parse(spec, text)` → `JsonElement`，校验失败就地提示、不写盘。
3. **执行期**：`RuleComponent.params` 直接交给动作实现（v1 仍经 `formatComponent` 桥到现有 `NativeTasks`，
   待动作实现迁移后再去掉这层）。
4. 显示文本永远由 `formatComponent(meta, params)` 生成，**不允许**用显示文本当输入再解析，
   避免「通知标题里带 `|`」「时长 1500ms 写法不同」这类有损往返。

## 6. 上下文传递

| 变量 | 来源 | 例子 |
| --- | --- | --- |
| `%{shell.stdout}` | `执行 Shell(Root)` 的标准输出 | 后续 `发送通知` 内容写 `%{shell.stdout}` |
| `%{http.body}` | `HTTP 请求` 的响应体 | 同上 |
| `%{last}` | 上一步的结果摘要 | 调试用，任何动作后都有 |

- 语法统一 `%{name}`；变量名限 `A-Za-z0-9_.-`。
- 未定义变量**保持字面量**，便于发现拼写错误而不是静默变空。
- 展开是递归的但**不重复展开**已展开结果：一个变量里含 `%{x}` 时不会被二次替换，避免自引用循环。
- v1 不提供全局变量持久化；变量只在一次运行内有效。

## 7. 编辑器

### 7.1 指令编辑页（唯一的任务列表）

任务的增删和排序都留在指令编辑页，**不设独立的「任务页」**：现有面板已经有
「添加 / 上移 / 下移 / 删除」，再开一页重复同一组操作只会多一层导航。

### 7.2 参数页

点击任务行直接进入动作的参数页（全屏，标题为动作名）：

- 参数表单：按 `ParamSpec` 生成控件；每个字段即时校验，错误就地显示，不写盘。
- 失败策略：三选一分段控件（`失败即停` / `继续执行` / `重试一次`）。
- 变量提示：`%{last}`、`%{shell.stdout}`、`%{http.body}`。
- Shell 参数同时包含 `eval` / `sh -c` 与 `%{…}` 时，显示黄色提示：变量值会被再次执行。
- 旧版 `legacy.unknown` 步骤：显示原始文本输入 + 「换成目录动作」。
- `完成` 或返回键回到指令编辑页，列表上的参数摘要随之更新。

### 7.3 与旧 UI 的关系

- 触发器、条件仍用原有玻璃面板（单参数、控件简单），动作改用新的参数页。
- 动作列表的每一行既是摘要也是入口；点击不再打开旧的单参数弹窗。

## 8. 未做 / 后续

1. 子任务调用与调用链；需要子任务、返回值和调用栈三件套。
2. 条件 / 循环块控制流——加入时编辑器需要缩进树，届时再单独设计。
3. 全局变量与跨任务持久化。
4. `HTTP 请求` 的 method/url/body 拆分：当前仍是 `GET url` / `POST url body` 单参数，
   拆分会改存储键，需要一次迁移，放到下一轮。

## 9. 变量展开与安全

`%{name}` 的替换方式由 `ParamSpec.expansion` 声明；执行器只按声明选择规则，不按组件
`typeId` 写特例。`PLAIN`、`SHELL_QUOTED`、`URL_ENCODED` 是三种基础方式；`HTTP_REQUEST`
是 HTTP 请求字段专用的复合方式。

| 展开方式 | 使用位置 | 行为 |
| --- | --- | --- |
| `PLAIN` | 通知内容、剪贴板文本等普通文本 | 变量值原样拼入 |
| `SHELL_QUOTED` | `action.root_shell` 的 `command` | 为每个变量生成一行 `FG_Vn='…'` 前导赋值；命令正文只保留变量引用，值本身不进入正文 |
| `URL_ENCODED` | 纯 URL 片段 | 按 UTF-8 做百分号编码；保留 `A-Z a-z 0-9 - . _ ~`，空格编码为 `%20` |
| `HTTP_REQUEST` | `action.http_request` 的 `request` | 只对 URL 段应用 `URL_ENCODED`，请求体保持 `PLAIN` |

**为什么 Shell 不能直接拼值**：变量可能来自 HTTP 响应、命令输出或以后的通知文字，内容不受本应用
控制。`;`、`&&`、`||`、`|`、`$()`、反引号和换行在 Shell 里都是语法；如果 `echo %{http.body}`
直接拼接，网页返回 `x; rm -rf /sdcard/重要目录` 就会以 Root 身份执行。

**值只出现在一处受控位置**：Shell 展开先为每个被引用的变量生成一行前导赋值，放在用户命令之前：

```sh
FG_V1='值'                 # 值中的 ' 写成 '"'"'
```

这一行完全由引擎生成，所在上下文可控，单引号转义在这里是可靠的。用户命令正文里的 `%{x}`
不再插入值，只替换为变量引用：

- 不在引号里：`"${FG_V1}"`
- 在双引号里：`${FG_V1}`
- 在单引号里：`'"${FG_V1}"'`
- 无引号 heredoc 正文里：`${FG_V1}`

引号状态跟踪只用来选择引用写法。即使判断错了，后果也只是多出引号或分词不同，值永远不会被
重新解析成命令。变量名只用 `FG_V1`、`FG_V2` 这样的序号名，不使用用户提供的名字，避免覆盖
`PATH` 等环境变量。`$()`、反引号和 heredoc 会开启新的命令上下文，展开器在进入和退出这些结构时
切换引用写法，保证其中的值仍是一个完整的变量展开。

**有意保留的边界**：如果用户自己写 `eval %{x}`、`sh -c %{x}`、`bash -c "$(…)"` 这类「再执行
一次」的命令，变量值本来就会被执行，这是用户的明确意图，引擎无法也不应该阻止。编辑页在 Shell
参数里同时检测到 `eval` / `sh -c` 与 `%{` 时，会显示一条黄色提示，提醒用户确认。

`HTTP 请求` 仍使用 `GET <url>` / `POST <url> <body>` 的单字段格式，因此用 `HTTP_REQUEST`
复合展开：先按空白切出 method、URL 和 body，只对 URL 段里的变量做百分号编码。变量值里的
`&`、`?`、`#`、`/`、空格会被编码，静态 URL 里的分隔符保持不变；body 里的内容按原文发送。

未知变量保持 `%{name}` 字面量；展开是单次替换，不会递归展开替换结果。
