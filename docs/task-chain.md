# 任务链设计（Task Chain v2）

本文是 `docs/task-flow.md` 的续篇。v1 只做「顺序步骤 + 变量展开」，并明确把条件/循环块留到
「编辑器需要缩进树」时再设计；本文补上这一层：块控制流、指令间串联、安全上限和手动停止。

范围是**设计**，不含代码。编码从 `0.7.0-alpha1` 开始，拆成 C1（引擎 + 最小界面）和
C2（编辑器界面）两个 PR，C2 基于 C1。

## 0. 结论摘要

| 问题 | 结论 |
| --- | --- |
| 存储 | **嵌套树**（`children` / `branches`），格式版本升到 3；平铺标记不采用 |
| 迁移 | 读 v2 → 包成单个根序列 → 写 v3；沿用 `LegacyFileMigration` 的 `commands.v2.bak.json` |
| 执行器 | `RuleExecutor` 内部改成递归解释器，外层签名不变；共享 `RunBudget` |
| 上限 | 1000 次/循环、10000 步/运行（跨调用累计）、5 层调用深度、保存时环检测；不设墙钟，长跑由用户停止 |
| 停止 | 每次运行持有 `Job`，取消即触发 `CancellationCheckpoint`；日志记「已手动停止」而非失败 |
| 条件 | 抽出 `ConditionEvaluator` 给触发器和块共用，新增 `condition.variable` |
| 变量 | 运行级 `RunContext.variables`、指令级 `Rule.locals`、全局 `RuleDocument.globals`，用 `run./rule./global.` 前缀区分 |
| 编辑 | 递归渲染 + 16dp/级缩进 + 折叠 + 长按拖动整块；不完整块就地提示、禁止启用（可保存草稿） |
| 版本 | C1 = `0.7.0-alpha1` / `versionCode 19`；C2 = `0.7.0-alpha2` / `versionCode 20` |

## 1. 数据结构：嵌套树

### 1.1 节点

`RuleComponent` 增加三个可选子结构（默认空，v2 文件可直接映射）：

```kotlin
@Serializable
data class RuleComponent(
    val type: String,
    val params: Map<String, JsonElement> = emptyMap(),
    val onError: OnError = OnError.STOP,
    // v3：块才有值，普通步骤保持空
    val children: List<RuleComponent> = emptyList(),      // if 的 then、loop 的循环体
    val branches: List<RuleBranch> = emptyList(),          // if 的 else-if / else
    val conditions: List<RuleComponent> = emptyList(),     // if 的分支、while 循环、wait_until
)

@Serializable
data class RuleBranch(
    val conditions: List<RuleComponent> = emptyList(),     // 空表示 else
    val children: List<RuleComponent> = emptyList(),
)
```

块类型（全部注册进 `BuiltInComponents`，`kind = ACTION`，这样现有的动作选择器、参数页、
层级徽标、权限提示都能直接复用）。识别块不要靠 typeId 前缀字符串，而是在 `ComponentMeta`
上加 `block: BlockKind?`（`IF/LOOP/WAIT/BREAK/STOP`），执行器和编辑器都按元数据分派；
以后新增块类型只改注册表，不动解释器。

| typeId | 参数 | 子结构 | 说明 |
| --- | --- | --- | --- |
| `block.if` | — | `children`（then）+ `branches`（else-if/else） | 分支条件复用条件组件 |
| `block.loop` | `mode = times/while`、`times`、`max`（默认 1000） | `children` | `while` 用块条件判断 |
| `block.wait_until` | `timeout`（默认 30 秒，5 秒–30 分钟）、`poll`（250ms–5 秒） | `conditions` 字段（见下） | 等待条件成立 |
| `block.break` | — | — | 只允许出现在 loop 内 |
| `block.stop` | `status = success/failure`、`message` | — | 提前结束整条任务 |
| `action.set_var` | `name`、`op = set/add/sub/mul/div/append`、`value` | — | 变量赋值/运算 |
| `action.run_rule` | `rule`（规则 id）、`wait = true/false` | — | 执行另一条指令 |

`block.wait_until` 的条件放在哪里？两种写法：`params.conditions` 存 JSON 数组（不能复用
`RuleComponent` 的参数校验），或者把条件作为 `branches`/`children` 的特例。推荐**新增
`conditions: List<RuleComponent>` 字段**（默认空），供三处使用：`block.if` 的
`branches[i].conditions`、`block.loop`（`mode = while`）的每轮判断、`block.wait_until`；
校验器保证其余类型该字段为空。

### 1.2 为什么不是「平铺步骤 + 开始/结束标记」

平铺标记（`block.if_start` / `block.if_end` + 深度）在这个代码库里代价更高：

1. **处处都要重建树**：编辑器要缩进、要折叠、要整块拖动，第一步就是把标记流解析成树；执行器要
   用栈解释；日志要算深度。三处各写一份，三处都可能对不齐。
2. **非法文档变成常态**：缺 `end`、`else` 落在错误深度、`break` 在循环外，都是可以存进文件的
   状态。现在的 `RuleJson` 只做参数校验，没有「结构合法」的概念，等于把一类崩溃留到运行时。
3. **块级失败策略无处安放**：`onError` 挂在 `RuleComponent` 上。平铺时「整个 if 失败」要靠
   「跳到对应 end」实现，跳转目标还得在解析阶段算好；嵌套时块就是一个节点，父序列照常处理。
4. **展示 sidecar 不友好**：`RulePresentation.actions` 是与 `actions` 平行的一串
   `ComponentPresentation(id, icon)`；平铺标记会让这个列表混入纯结构项，而嵌套树可以改成
   `items: Map<Long, ComponentPresentation>`（按稳定 id 查，和层级无关）。

嵌套的唯一缺点是 schema 变了，需要一次迁移；而这次迁移恰好是「加默认空字段 + 版本号 +1」，
没有字段改写（见 1.4）。

### 1.3 文档形态

```json
{
  "formatVersion": 3,
  "rules": [{
    "id": "r1", "name": "到家", "type": "auto",
    "triggers": [{ "type": "trigger.wifi", "params": { "state": "connected" } }],
    "actions": [
      { "type": "action.set_var", "params": { "name": "run.retry", "op": "set", "value": "0" } },
      { "type": "block.loop", "params": { "mode": "while", "max": 5 },
        "conditions": [{ "type": "condition.variable",
                         "params": { "left": "{retry}", "op": "lt", "right": "5" } }],
        "children": [
        { "type": "action.http_request", "params": { "request": "GET https://api.example/status" },
          "onError": "continue" },
        { "type": "block.if", "params": {}, "children": [
          { "type": "action.post_notification", "params": { "content": "{HTTP响应}" } }
        ], "branches": [
          { "conditions": [], "children": [
            { "type": "action.delay", "params": { "duration": 2000 } }
          ]}
        ]}
      ]},
      { "type": "action.run_rule", "params": { "rule": "r2", "wait": true } }
    ]
  }],
  "globals": { "last_city": "上海" }
}
```

### 1.4 迁移与备份

- `CURRENT_FORMAT_VERSION` 2 → 3。读 v2：`children` / `branches` / `conditions` 取默认空、
  `formatVersion` 改 3，规则语义完全不变（现有顺序任务就是「根序列」）。
- 写盘走 `CommandStore` 现有的 `AtomicFile`；迁移前用 `LegacyFileMigration.backupBeforeWrite`
  留 `commands.v2.bak.json`（同名不覆盖、同内容复用、不同内容编号），与 v1 迁移完全一致。
- 迁移失败（解码/校验异常）沿用现有策略：`blocked = true`，保留原文件、禁止覆盖写，UI 顶部
  提示「本地数据读取失败，原文件已保留」。**任何情况下不删原文件。**
- 降级：v3 文件不能被 v2 代码读取（`ignoreUnknownKeys = false`），所以 v3 之后不提供旧版回退；
  需要回退时用设备上的 `.bak.json` 手动替换。这一点写进发布说明。
- 展示 sidecar：`RulePresentation` 的 `triggers` / `conditions` / `actions` 三个列表合并为
  `items: Map<Long, ComponentPresentation>`，迁移时按 id 摊平（id 已是稳定字段，不重新生成）；
  层级只存在于引擎的树里，展示层按 id 查图标，不重复存结构。

## 2. 执行器

### 2.1 递归解释器

`RuleExecutor.run(rule, reason, initialVariables)` 的对外签名保持不变，内部把「for 循环」换成：

```
run(): budget = RunBudget(10_000 步)
       ctx = RunContext(rule, reason, vars)
       root = rule.actions
       outcome = execSequence(root, depth = 0)

execSequence(nodes, depth):
  for node in nodes:
    budget.ensureAlive()                 // 步数；超限抛 BudgetExceeded
    checkpoint.ensureActive()            // 手动停止 + 协程取消
    when kind(node):
      STEP  -> 现有动作分支（参数展开、执行、RETRY、output 写回）
      IF    -> 依次评估 branches/then，命中则 execSequence(children, depth)
      LOOP  -> 最多 min(param.max, 1000) 次；while 每轮前评估 node.conditions
      WAIT  -> 轮询条件，超时返回 FAILURE("等待超时：…")
      BREAK -> 抛 BreakSignal，最近 loop 捕获
      STOP  -> 抛 StopSignal(status, message)，run() 捕获并定型
      CHAIN -> 调 RuleCaller.call(ruleId, depth + 1)
```

- 失败语义沿用 v1：步骤失败且 `onError != CONTINUE` 时，**当前序列**停止，块以
  `FAILURE` 返回给父序列；父块自己的 `onError` 再决定「停 / 继续 / 重试一次」。
- `RETRY` 只保留在**步骤级**（失败后立即重试一次）；块级 `onError` 只允许 `STOP` / `CONTINUE`，
  避免整块重跑放大副作用。
- `SKIPPED` 不计失败：`if` 条件全不成立且没有 `else` → 整块 `SKIPPED("没有命中的分支")`；
  `loop` 次数为 0 → `SKIPPED("循环体未执行")`。
- 取消（手动停止）不写终态：`CancellationException` 一路抛出，由外层包装成
  `status = SKIPPED, detail = "已手动停止"` 的日志，不进入失败统计。

### 2.2 安全上限

| 上限 | 位置 | 计数方式 | 超限行为 |
| --- | --- | --- | --- |
| 循环 1000 次 | 每个 loop 节点 | 每轮 +1（嵌套循环各自计数） | 该块 `FAILURE("超过单循环 1000 次")` |
| 10000 步/运行 | `RunBudget` | 每执行一个节点 +1，含块头、`wait`（整个等待只计 1 步）、被调用指令的全部步骤 | 整次运行 `FAILURE("超过单次运行 10000 步")`，剩余步骤不再执行 |
| 5 层调用 | `RuleCaller` | 直接调用 = 1；被调用指令再调 = 2 | `FAILURE("调用层级超过 5 层")` |
| 环 | 保存 + 加载 | 见 2.5 | 保存拒绝；加载时该规则标记非法并禁用 |

`wait` 整个等待只计 1 步（每次轮询不再计步），超时范围 1 秒–1 小时；轮询间隔 250ms–5 秒，
**每次轮询前**都调用 `checkpoint.ensureActive()`，所以等待期间也能被手动停止。不设运行墙钟：
防死循环靠 10000 步上限，长时间运行由用户停止。

### 2.3 手动停止

现在 `AppState.execute()` 已经把每次运行放进 `ExecutionRuntime.scope` 并返回 `Job`，但没有
任何人保存或取消它。改动：

- `AppState` 增加 `runs: MutableMap<Long, Job>`（key = `cmd.id`），`execute()` 存入，结束时清理。
- 运行中的卡片 / 编辑页头部 / 执行记录里的「运行中」行显示「停止」按钮，点击
  `job.cancel()` + `executionTokens` 失效，UI 立即把状态置为「已停止」。
- 触发器发起的运行走同一个 `execute()`，所以历史面板也能停。
- 取消是协作式的：`RuleExecutor` 在每个节点、每轮循环、每次 `wait` 轮询前调用
  `checkpoint.ensureActive()`；阻塞型动作（Shell/HTTP）已有的 `BoundedProcess` 会响应取消并杀进程组。
- 指令串联：`wait = true` 的子运行用 `coroutineScope` 结构化并发，父运行取消时一起取消；
  `wait = false` 的子运行是独立任务（见 2.5），父运行取消不影响它。

### 2.4 日志

`StepResult` 增加 `path: List<Int>`（例如 `[1, 0]` = 第 2 个根节点的第 1 个子节点；分支用
`branchIndex` 拼在 path 里）和 `nodeId: Long?`（对应展示 sidecar 的 id）。保持「按执行顺序
平铺」的列表，现有历史面板不改也能显示；新 UI 用缩进渲染。

必须一起改的地方：`ExecutionEngine` 现在用 `cmd.actions.getOrNull(index)` 反查步骤对应的
`CommandItem`，嵌套后 index 不再等于根层下标，需要用 `nodeId`（引擎带回）或 `path` 反查；
否则步骤的图标和名称会错位。

### 2.5 指令串联与环

- 新动作 `action.run_rule`：`rule` 选目标指令，`wait = true`（默认，等执行完再继续）/
  `false`（发起后立即继续）。
- 同步调用（`wait = true`）：子运行用**共享的 `RunBudget`**（步数累计）和 `depth + 1`，
  结构化并发；子运行状态映射为父步骤结果：成功 → SUCCESS，失败/超时 → FAILURE（父块的
  `onError` 生效）。
- 异步调用（`wait = false`）：通过 `AppState.execute` 发起一次**独立运行**——独立执行记录、
  独立 `Job` 与停止按钮、独立步数预算；父步骤立即 SUCCESS（日志「已发起」），父运行结束或
  取消都不影响它。`depth` 仍然 +1，并参与环检测。
- 变量：子运行拿父运行变量的**只读快照**，自己写自己的 `run.` 作用域，不回传（v1 不做返回值）。
- 环检测：把每个 `action.run_rule` 当成有向边，`TaskChainValidator` 在**保存时**做 DFS
  （含 `wait = false` 的边，异步也能无限递归），发现环返回
  `ValidationIssue(path, "指令调用形成环：A → B → C → A")`，保存直接拒绝；自调用算环。
- 加载时同样跑一次校验（防止手工改文件/旧版本写入）：环上的规则 `enabled = false` 并打
  「调用成环」标记，不会执行，但数据保留。
- 被调用指令**只执行它的动作**，不检查它自己的触发器和条件；允许跨「自动指令 / 一键指令」调用。
- 目标不存在：保存时是**警告**（允许先存草稿），运行时该步骤 `FAILURE("目标指令不存在")`。

## 3. 条件与变量

### 3.1 复用现有条件组件

`TriggerConditions.check()` 目前是 `private`，且只认 5 个条件（时间范围、星期、电量范围、
充电、屏幕）。抽成引擎公开的：

```kotlin
interface ConditionEvaluator {
    fun evaluate(
        condition: RuleComponent,
        variables: Map<String, Any?>,
        environment: ConditionEnvironment,   // 时间/电量/屏幕…，由宿主提供
    ): Boolean
}
```

- 触发器路径：`TriggerConditions.evaluate` 改为调用同一个 `evaluate`，行为不变（回归测试锁住）。
- 任务路径：块条件传当前 `RunContext.variables`，所以能读 `run.` 变量。
- 触发器路径：传入事件变量（如 `{电池电量}`、`{触发时间}`），条件里可以直接使用内置变量；不传整个 `RunContext`，因为触发器侧没有 Rule。
- 现有 5 个条件之外，`condition.dnd_state` / `condition.network_type` 目前是 `comingSoon = true`
  （编辑器不可选，引擎里也恒为 false）。块的条件列表要沿用同一过滤，不把 `comingSoon` 组件放进去；
  否则会出现「选了但永远不成立」。补实现留到后续 PR。
- 新增 `condition.variable`：`left`（变量选择或字面量路径）、`op`（`gt` / `ge` / `lt` / `le` / `eq` / `ne` / `contains` / `not_contains` / `unset` / `set` / `empty` / `not_empty` / `file_exists` / `file_not_exists`）、
  `right`。两边都能解析成数字时按数值比较，否则按字符串比较；未定义变量 → 不成立，detail 写明变量名。

### 3.2 变量作用域

| 作用域 | 语法 | 存储 | 生命周期 | 上限 |
| --- | --- | --- | --- | --- |
| 局部 | `{x}` | `Rule.locals`（commands.json） | 运行内；`resetEachRun = true` 时每次运行重置为 `initial` | 50 个键/规则 |
| 全局 | `{x}` | `RuleDocument.globals: Map<String, JsonElement>` | 跨规则、跨运行；设置页可清空 | 100 个键、总计 16KB |
| 内置（只读） | `{x}` | 运行时提供 | 单次运行/当前事件 | — |

- 所有变量统一写成 `{名字}`，没有 `%{}`、`global.`、`rule.`、`run.`、`event.` 等旧前缀；解析顺序为局部 → 全局 → 内置。
- `Rule.locals` 的 `type` 为 `int` / `boolean` / `text` / `int_list` / `boolean_list` / `text_list`；`resetEachRun` 决定运行开始时是否重置为 `initial`。
- 指令级与全局变量在运行结束时写回（**成功或失败都写**，手动停止不写，崩溃不写）；写回按键合并到现有值，不整表覆盖，避免把并发运行刚写入的键抹掉。
- `action.set_var` 的 `name` 只写名字；由 `scope`（`local` / `global`）决定范围，`type` 决定值类型。
- 变量名允许中文，不允许空格和点，大小写敏感，长度 1–64。

## 4. 编辑页

### 4.1 缩进、折叠

- 递归渲染：每个块头一行（图标、块名、条件摘要、折叠箭头、`⋯` 菜单），子序列整体右移
  **16dp/级**，左侧画一条 2dp 竖引导线（与卡片圆角对齐）。
- 折叠：点块头箭头收起子树，显示「已折叠 · N 步」；折叠状态存在 `RulePresentation` 里
  （`collapsed: Set<Long>`），重启后保持。默认展开。
- 深度超过 4 级时，块头显示「第 N 级」小徽标，减少「不知道自己在哪」。
- 大块（>50 步）在编辑页底部显示「共 N 步」并可折叠全部/展开全部。

### 4.2 拖动整块

- 长按块头 300ms 进入拖动（`detectDragGesturesAfterLongPress`），整棵子树跟随手指，原位置
  留半透明占位；拖动时其它行让位（`animatePlacement` 已有）。
- 落点 = 任意层的「同级之间」，插入线 + 目标缩进提示；拖到块头的右侧热区可**放入块内**。
- 禁止：拖进自己的子树（高亮变红并回弹）、把 `break` 拖出循环（就地提示「跳出循环只能在循环内」）。
- 拖动只改内存里的树，松手后走现有 `save`；撤销条复用现有 `UndoBar`（一次拖动一条撤销）。
- 兜底：块头 `⋯` 菜单保留「上移 / 下移 / 移入上一块 / 移出当前块」，键盘和读屏也能操作。

### 4.3 不完整块提示

保存与启用分开：**草稿可以保存，未通过校验的指令不能启用**（开关置灰 + 说明），避免用户丢工作。

| 情况 | 提示位置 | 文案 |
| --- | --- | --- |
| if 没有条件 | 块头 + 首行 | ⚠ 这个「如果」还没有条件 |
| if 的 then 为空 | 块内空行 | 条件成立时什么也不做，点「添加」 |
| loop 体为空 | 块内空行 | 循环体为空，运行时会被跳过 |
| wait 没有条件 | 块头 | ⚠ 等待缺少条件，会一直等到超时 |
| break 不在循环内 | 行内红字 | 跳出循环必须放在循环里 |
| 串联目标不存在 | 行内黄字 | 目标指令已删除，运行时这一步会失败 |
| 调用成环 | 顶部横幅 + 两行 | 指令调用成环：A → B → A（保存被拒绝） |

示意（缩进 + 折叠 + 不完整提示）：

```
┌ 任务 ─────────────────────────────── 12 步 ┐
│ 🔁 循环 · 最多 5 次                    ⌄  │
│    ⚙ HTTP 请求  GET …/status              │
│    ❓ 如果                              ⌄  │
│        ⚠ 这个「如果」还没有条件            │
│        ＋ 添加条件                         │
│    ↪ 跳出循环                              │
│ ▶ 如果（已折叠 · 3 步）                    │
│ ⏱ 等待直到 · 屏幕亮起（超时 30 秒）        │
│ ⛔ 调用成环：A → B → A                     │
└────────────────────────────────────────────┘
```

## 5. PR 拆分

### C1 `feat/task-chain-engine`（0.7.0-alpha1 / versionCode 19）

内容：

1. 数据模型 v3（`children` / `branches` / `conditions` / `globals`）+ 迁移 + `.bak` 备份 +
   sidecar 改 id 映射。
2. 递归执行器：块语义、`RunBudget`（只计步数，无墙钟）、`BreakSignal`/`StopSignal`、
   日志 `path/nodeId`、`ExecutionEngine` 反查修正；块级 `onError` 只有 STOP / CONTINUE。
3. 条件：`ConditionEvaluator` 抽取 + `condition.variable` + 事件变量 + 四个作用域的初始化/写回
   （运行级 / 事件级 / 指令级 / 全局；结束时按键合并）。
4. 串联：`action.run_rule` 同步（共享预算 + 结构化并发）与异步（独立 `AppState.execute` 运行）
   + `RuleCaller` + 保存/加载环检测 + 深度限制。
5. 手动停止的引擎侧（取消语义、日志「已手动停止」）与 `AppState.runs` + 停止按钮（最小界面：
   列表行内出现「停止」）。
6. 最小编辑器：块类型注册进组件表，添加面板能插入块、缩进显示、上下移/删除；暂不做折叠和拖动。

测试：

- engine（`testAndroidHostTest`）：if/else-if/else 命中与穿透、loop 次数/while/break/嵌套、
  wait 超时与轮询、step 上限（用 10 步的小预算注入）、循环上限、深度上限、环检测、错误策略
  在块内的传递、`StepResult.path` 顺序、取消不写终态。
- androidApp（`testDebugUnitTest`）：v2→v3 迁移与备份（同 v1 迁移测试的写法）、sidecar 摊平、
  `ExecutionEngine` 的 nodeId 反查、串联同步/异步状态映射。
- 模拟器冒烟：if 真/假、loop 3 次、wait 2 秒后成立、10000 步上限用小预算版验证、停止按钮。

### C2 `feat/task-chain-editor`（基于 C1，目标 main，描述注明「基于 C1」；0.7.0-alpha2 / versionCode 20）

内容：

1. 递归渲染 + 缩进引导线 + 折叠（持久化）。
2. 长按拖动整块（含禁止拖入子树、拖出循环的提示）+ `⋯` 菜单兜底。
3. 不完整块的就地提示、顶部校验横幅、未通过校验时禁止启用。
4. 变量面板：三个作用域的列表、编辑、清理；`{…}` 插入提示。
5. 串联选择器：目标指令列表（含搜索、被删目标标记）、同步/异步开关、成环时的错误展示与跳转。
6. 运行中停止入口的完整 UI（卡片、编辑页、历史面板）。

测试：

- Robolectric/Compose 能测的部分：树形渲染的层级与折叠、拖动重排的树结果、校验横幅文案、
  变量面板增删改。
- 其余靠设备冒烟：折叠后重启保持、拖动整块后执行顺序正确、删除被引用指令的警告、停止按钮。

### 可选 C3（全局变量与调用返回值，0.7.0-alpha3）

`globals` 的完整 UI、跨指令返回值（例如 `{上一步结果}`）、变量历史与清理策略。本文只定存储和上限，
实现留到 C3，避免 C2 过大。

## 6. 测试与验收

- 每个 PR 的 CI 门槛与现在一致：engine + androidApp 单测、`lintRelease`、release APK。
- 新增测试不依赖真实时间：`Clock` 注入 + 虚拟时间推进；`wait` 的轮询在测试里降到 1ms（整个等待仍只计 1 步）。
- 上限测试用小预算版本（`RunBudget(maxSteps = 10)`）验证计数与错误文案，避免跑一万步。
- 迁移测试必须覆盖：v2 原文件字节完全不变、`.bak.json` 内容等于原文件、迁移后可回读、
  二次启动不再重复迁移。
- 设备冒烟清单（写入 PR）：见 C1/C2 两条。

## 7. 风险

1. **迁移 + sidecar 改形**：v2→v3 理论上只是加默认字段，但 `RulePresentation` 从列表改成 map
   会碰所有保存路径；一旦漏掉某个保存入口，可能出现「规则在、图标丢」。缓解：迁移用现有
   `backupBeforeWrite`、sidecar 转换写单测、加载后再保存一次做往返比对。
2. **执行语义变化**：块内 `onError` / `SKIPPED` / 取消的组合容易出微妙 bug（例如失败块向上
   传递、取消后仍写了终态）。缓解：引擎层穷举用例 + 日志断言；`RunBudget` 只在解释器内部，
   不散落到动作实现。
3. **编辑器交互**：拖动整块在玻璃面板上容易和滚动手势冲突。缓解：长按 300ms 才进入拖动、
   提供菜单兜底、C2 单独冒烟。

## 8. 已确认（0.7.0 实现口径）

- 不设墙钟上限；死循环由 10000 步拦住，长跑由用户手动停止。
- `wait` 整个等待只计 1 步；超时 1 秒–1 小时；轮询前检查取消。
- 块级 `RETRY` 不做，块 `onError` 只有 STOP / CONTINUE；步骤级 RETRY 不变。
- 允许跨类型调用；被调用指令只跑动作，不看自己的触发器/条件。
- 异步调用是独立运行（独立记录、Job、停止、预算），父运行取消不影响它。
- `versionCode` 18 留给 0.6.1；C1 用 19、C2 用 20。
