# Tasker 任务流程、参数传递与变量传递（6.7.7-rc）

本文只讲“任务怎么跑、参数怎么进、变量怎么传、前后任务怎么接”。
数据格式细节见 `Tasker任务XML与参数编码.md`，执行器内部结构见 `Tasker任务执行实现方案.md`。

证据：`jadx/sources/net/dinglisch/android/taskerm/`（类名 `Cxxxxx原名`）与 `apktool/smali*`。
带「推断」的条目是依据参数名/官方内置目录，未能追到最终执行分支（分派器 `m80481d4` 未反编译）。

---

## 0. 一句话模型

一条任务 = 动作数组 + 一个执行索引；每次取当前动作，处理块语义后分派执行，
再用结果码决定“下一条 / 回退 / 失败 / 停止”。任务之间通过“新建一个带 lvars 的执行请求”衔接。

---

## 1. 任务生命周期

### 1.1 触发入口

| 来源 | 入口 |
| --- | --- |
| 外部 App / adb | `ACTION_TASK` 广播 → `ReceiverStaticRunTasks` |
| 桌面快捷方式 / Widget | 直接向 `ExecuteService` 起 intent |
| Profile 命中 | `MonitorService` 内部调度 |
| 手动运行 | UI 调 `ExecuteService.m80402T5` |

外部广播进 `MonitorService` 时带 `eventType=9989`，最终都落到 `ExecuteService.m80546k5(Bundle, boolean)`。

### 1.2 入队（`ExecuteService.java:14634`）

```
mcro     任务序列化数据（必需，缺失直接丢弃）
flags    位标志（bit0 绕过禁用检查，bit1/2/3/4 设置任务内部状态）
gid      任务组 id
ppi      优先级（覆盖）
ppri     优先级（第二顺位）
sc       场景名（场景任务）
lvars    局部变量 Bundle
```

检查顺序：

1. `mcro` 为空 → 丢弃。
2. Tasker 被禁用且 `flags&1==0` → `RejOff`。
3. 同任务重入 → `RejCopy`。
4. 动作数 0、块不配对 → `RejBad`（不配对时先尝试自动补 `End If/End For`）。
5. 队列满（`mqt`，默认 10）→ `RejMaxQ`。
6. 分配 `execId`/`pid`，写 `%priority`，加入队列，记 `RunLog.Running`，起工作线程。

### 1.3 执行（`ExecuteService.java:824` 的 `run()`）

```
while (!stopped) {
    task = 取可运行任务
    %qtime = 排队耗时
    %tasker_current_action_number = 当前索引 + 1
    action = task.当前动作()
    result = m80331K2(task, action, 索引, flag)
    switch (result) { -1 前进 / -2 回 For / -3 失败 / -5 跳过 / -7 条件失败 }
}
```

### 1.4 结束

- 任务状态：`Running / ExitOK / ExitErr / ExitKill / Rej*`（`C20813jn.e`）。
- 写 `RunLog`；外部调用方收到 `ACTION_TASK_COMPLETE`（data scheme `task://<任务名>`）。
- 局部变量 Bundle 随任务对象释放；全局变量保留。

---

## 2. 任务内流程

### 2.1 结果码

| 码 | 含义 | 后续 |
| --- | --- | --- |
| `-1` | 正常/继续 | 索引 +1 |
| `-2` | `End For` 命中，回到 `For` 头 | 重新求值循环 |
| `-3` | 动作失败 | 看 `se`（默认 true）→ 停止任务 |
| `-5` | 动作被禁用 | 跳过，索引 +1 |
| `-7` | `If` 条件为假 | 前进到 `Else/End If` |

### 2.2 控制流的推进方式

- `If(37)`：求值后把结果写到**该 If 动作对象**的 boolean 字段（`C20588c.f72913F`）。
- 条件为假：`skipToEndOfBlock(task, 37, 43, 38, false)`（`ExecuteService.java:7619`），
  深度计数找 `Else` 或 `End If`。
- `Else(43)`：`m80646v5` 回找最近 If 头读结果；真分支已执行则跳过 else 块。
- `For(39)`：条件不成立 → 跳到 `End For(40)`；`End For` 命中 → `skipToStartOfBlock(39,40)`
  （`:7924`）退回 For 头重新循环。
- `Wait(30)` 阻塞当前执行线程；`Wait Until(35)` 循环求值直到条件成立或超时。
- `Goto(135)` 直接改索引/按标签跳；`Stop(137)` 置停止标志，可带 `with_error` 与目标任务名。

### 2.3 结构校验

`C20813jn.m86381p0` 用栈扫描：`For→End For`、`If→End If` 必须配对；
入队时传 `true` 会自动补缺失的闭合动作。

---

## 3. 任务间衔接（重点）

### 3.1 Perform Task（动作 130）参数表

XML 里按 `arg0..arg10` 顺序：

| arg | 名称 | 类型/范围 | 作用 |
| --- | --- | --- | --- |
| 0 | 任务名 | `m:1` | 目标命名任务 |
| 1 | 优先级 | `0:50` | 子任务执行优先级 |
| 2 | 参数 1 | text | 子任务里的 `%par1` |
| 3 | 参数 2 | text | 子任务里的 `%par2` |
| 4 | 返回值变量 | `uvar:1:?` | 接收子任务 `%return` |
| 5 | 停止 | toggle | 先停止已在运行的子任务（推断） |
| 6 | 局部变量直通 | toggle | 把父任务全部局部变量传给子任务 |
| 7 | 将直通限制为 | text | 直通变量名单（推断） |
| 8 | 重置返回的变量 | toggle | 调用前清空返回值变量 |
| 9 | 允许覆写变量 | toggle | 允许子任务覆盖父任务变量 |
| 10 | 结构输出 | `bosta` | 返回值按 JSON/XML 结构化解析 |

### 3.2 子任务是怎么被“创建”的

`ExecuteService.m80402T5` → `m80654w4`（`:17830`）构造一个指向 `ExecuteService` 的 Intent：

```
mcro   = 子任务序列化（C21098ti → 字符串）
flags  = 调用方指定
gid    = 任务组
ppi/ppri = 优先级
lvars  = 局部变量 Bundle（含 par1/par2/return/passthrough 等键）
sc     = 场景
```

`C20586bq.m84823d(str, list, bundle)` 往 `lvars` 写调用链：

```
%caller1 = 直接调用者任务名
%caller2..N = 祖先链
m84737B(bundle, N) 清掉多余的旧 %callerN
```

所以“前后任务衔接”本质是：**父任务把子任务序列化 + 变量包，作为一次新的执行请求投递**。
父任务是否等待、返回变量何时可读，由 Perform Task 的等待/返回语义决定；
执行器侧能确认的是返回值存储在子任务对象的 `f73939e0`（`m86295H2` 写、`m86373m1` 读）。

### 3.3 返回值

返回值走同一条 lvars 通道：启动方把 `return` 键放进 `lvars`，
新任务构造时读入并保存在任务对象（`C20813jn.f73939e0`，写入方法 `m86295H2`，`ExecuteService.java:14746`），
对外读取用 `m86373m1`（`C20813jn.java:1523`）。

- Profile 的 enter/exit 任务也用这条通道传 `return`（`MonitorService.java:3813/11187`）。
- Perform Task 的“返回值变量”由父任务在子任务结束后读取该值并赋给指定变量；
  具体赋值的分支在未反编译的 `m80481d4` 内（推断）。
- “重置返回的变量”用于避免上一次调用的旧值被误读（参数语义，实现点未定位）。
- 结构化输出打开时，返回值按 JSON/XML/CSV 解析成结构化变量（推断，依 `bosta` 语义）。

### 3.4 局部变量直通

- 打开“局部变量直通”后，父任务当前所有局部变量被复制进子任务的 `lvars`；
  子任务开头即可直接引用同名变量。
- “将直通限制为”指定只传哪些变量（推断）。
- “允许覆写变量”控制子任务能否改写父任务同名变量（推断）。
- 变量多时优先用直通，而不是塞 `%par1/%par2`（只有两个位置）。

### 3.5 停止与优先级

- `Stop(137)`：`arg0=with_error`、`arg1=任务名`；不指定任务名时停止当前任务。
- Perform Task 的 `停止` 参数用于先终止同名子任务，避免叠加运行。
- `优先级` 与入队的 `ppi/ppri` 共同决定调度顺序；队列上限默认 10（`mqt`）。

### 3.6 其它衔接方式

| 方式 | 特点 |
| --- | --- |
| 外部 `ACTION_TASK` | 跨 App，需要 `PERMISSION_RUN_TASKS`，可带 `lvars`/`par1`/`par2` |
| Command System | 走 `command=:=...`，中间多一层 Profile，解耦但更绕 |
| Widget / 快捷方式 | 点击直接起任务，可带 `taskVariables` |
| 子任务完成回调 | `ACTION_TASK_COMPLETE`，按 `task://<name>` 过滤 |

---

## 4. 参数传递（动作级）

### 4.1 结构

每个动作的 `argN` 按动作定义顺序生成；类型由定义决定：

```
<Action sr="act0" ve="7">
  <code>123</code>
  <Str sr="arg0" ve="3">ls /sdcard</Str>
  <Int sr="arg1" val="30"/>
  <Int sr="arg2" val="0"/>
</Action>
```

- `Str` 文本，`Int` 数字（`val` 或 `<var>%x</var>`），`Bundle` 嵌套，`Img` 图标，`App` 应用。
- 缺失的可选参数可以不写；必填参数缺失会按默认值/空值处理，严重时动作失败。

### 4.2 参数规格前缀

注册表里每个参数带 `type/name/default`，编辑器据此决定控件与语义：

```
uvar:1      变量名（可含 %）
m:1         任务名
t:3         多行文本
f:2         文件路径
s:5         大文本
js:999      JS 代码
inpval:3    可含变量的值
bosta       结构输出开关
col:1       颜色
```

### 4.3 展开时机

参数在**读取时**展开（`C20588c.m84916D0/m84918E0` → `C20586bq` 的取值链），不是写入时。
所以：

- 同一个 `%v` 在不同动作执行时取到不同值；
- 循环里改 `%v` 会立刻影响后续动作；
- 变量里再引用变量会递归展开（`m84753G0` / `m84873t1`，支持 `%var(%i)` 与动态前缀）。

---

## 5. 变量传递

### 5.1 作用域

| 类型 | 命名 | 存储 | 生命周期 |
| --- | --- | --- | --- |
| 全局 | 含大写（`%MyVar`） | SharedPreferences | 跨任务、跨重启（除非清空） |
| 局部 | 全小写（`%myvar`） | 任务 Bundle | 本次任务运行 |
| 参数 | `%par1` / `%par2` | 子任务 lvars | 子任务运行 |
| 调用链 | `%caller1..N` | 子任务 lvars | 子任务运行 |
| 返回 | `%return` | 子任务 lvars → 父任务变量 | 由父任务读取 |

### 5.2 查找顺序

取值走 `getValueLOrG`（`C20586bq.m84753G0`，`:898`）：先局部 Bundle，再全局存储。
未定义时保留字面量（`%foo` 原样返回），这点在拼接字符串时要特别注意。

### 5.3 执行器写入的变量

```
%err                          动作失败码
%errmsg                       失败消息
%priority                     当前任务优先级
%qtime                        排队耗时(ms)
%tasker_current_action_number 当前动作序号（1 基）
%caller1..%callerN            调用链
```

### 5.4 数组与结构化

- 数组是“同前缀 + 数字”的变量族：`%arr1..%arrN`，用 `%arr(#)` 取长度、`%arr(2:4)` 切片。
- `For` 的 `arg1` 支持 `1:%arr(#)` 这种表达式。
- 动作的 `bosta` 参数打开后，变量可按 JSON/XML/CSV 访问（`%json.a.b`、`%html[...]`）。

### 5.5 常见坑

1. 局部变量只在本次任务有效；跨任务必须显式传参/直通/写全局。
2. `%par1/%par2` 只有两个位置，超过就用直通或结构化返回。
3. 变量未定义不会报错，会原样保留 `%xxx`，容易把字面量当值用。
4. 返回值变量不重置时可能读到上次的值，需要开“重置返回的变量”。
5. `%callerN` 是调用时快照，子任务里改不了父任务的链。

---

## 6. 错误与状态传播

- 动作失败 → `%err`/`%errmsg` 写入；`se=true`（默认）时任务以 `ExitErr` 结束。
- `se=false`（XML `<se>false</se>`）时继续执行下一条，调用方自行检查 `%err`。
- 入队失败不执行任何动作，只记 `RunLog` 的 `Rej*` 状态。
- 父任务调用子任务时，子任务失败通常通过返回值/`%err` 体现（推断，取决于父任务是否检查）。

---

## 7. 典型组合

### 7.1 父任务带参调用子任务

```xml
<!-- 父任务 -->
<Action sr="act0" ve="7">
  <code>130</code>
  <Str sr="arg0" ve="3">子任务名</Str>
  <Int sr="arg1" val="10"/>
  <Str sr="arg2" ve="3">hello</Str>
  <Str sr="arg3" ve="3">%TIME</Str>
  <Str sr="arg4" ve="3">%result</Str>
</Action>
```

子任务开头直接用 `%par1` / `%par2`。

### 7.2 子任务返回

```
子任务: Variable Set %return = %par1 + "-ok"   → 结束
父任务: Perform Task 返回值变量 = %result     → 后续读 %result
```

### 7.3 大量变量传递

```
Perform Task 勾选“局部变量直通” → 子任务直接引用父任务的 %a/%b/%c
```

### 7.4 顺序执行与停止

```
主任务: Perform Task(子任务A) → Wait Until %A_done=1 → Perform Task(子任务B)
子任务A 结束前: Variable Set %A_done=1（全局）
```

---

## 8. 与 ShortX 对照

| Tasker | ShortX | 注意 |
| --- | --- | --- |
| 动作数组 + 索引 | 动作链 | 执行模型可 1:1 对应 |
| If 结果存块头 | 条件组件 | 嵌套需显式建模 |
| For 回退块头 | 循环动作 | 变量作用域不同 |
| Perform Task + %par1/2 | 执行任务 + 参数 | 需要显式参数表 |
| 局部变量直通 | 参数透传 | 注意变量名冲突 |
| %return | 任务返回值 | 需要重置语义 |
| %callerN | 调用链 | 可选实现 |
| 全局变量（大写） | 全局变量 | 持久化位置不同 |

---

## 9. 证据与边界

已验证：

- 入队字段与拒绝原因：`ExecuteService.java:14634-14780`
- 结果码与状态机：`ExecuteService.java:1019-1100`、`7729-7807`
- 块跳转：`ExecuteService.java:7619`、`7924`、`13954`、`17708`
- 结构校验与自动补全：`C20813jn.java:1561`
- 子任务 Intent 构造：`ExecuteService.java:17830`
- 调用链写入：`C20586bq.java:2187`
- 返回值存储：`C20813jn.java:706`、`1523`、`14746`
- 变量展开：`C20586bq.java:898`、`3075`

推断（未追到最终分支）：

1. Perform Task 的“停止/直通限制/允许覆写/重置返回”在分派器 `m80481d4` 内的具体实现。
2. 父任务是否阻塞等待子任务结束（Tasker 的 Perform Task 默认异步；返回值语义依赖运行时）。
3. `bosta` 结构输出在返回值上的解析细节。

要补这三点，只能做动态验证：构造两条最小任务，用 `%par1`、`%return`、`%caller1` 实际跑一遍并看 RunLog。
