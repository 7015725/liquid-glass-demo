# Tasker 6.7.7-rc 任务执行实现方案（静态逆向）

分析对象：`net.dinglisch.android.taskerm` **6.7.7-rc**（versionCode 5453，minSdk 27 / targetSdk 36）。

APK：`../../apk/net.dinglisch.android.taskerm-20261017-121917.apk`（46,755,939 字节）
SHA-256：`1afc60d05c4a6dae73d982aeff61ad2570b9c524e112df727c94b40263e0b389`（与设备安装的 base.apk 逐字节一致）

证据：本 run 的 `apktool/`（6 dex 全量 smali，53,323 文件）与 `jadx/sources/`（21,815 java，deobf）。
类名按反编译结果写 `Cxxxxx原名`，可直接在 `jadx/sources/net/dinglisch/android/taskerm/` 检索。

> 边界：纯静态读取，未启动应用、未触发任务、未改数据。JADX 以 312 个方法级错误结束（exit 3），
> 最关键的单动作分派 `ExecuteService.m80481d4`（7,266 指令）未能反编译为 Java，
> 本文对它的描述来自调用点、返回结构与 smali 常量，不逐 action 展开。

---

## 0. 结论先看

1. 一个 Task 就是**动作数组 + 元数据**，执行模型是**单线程按索引推进的状态机**，不是字节码/跳转表。
2. 控制流不是"跳转到行号"，而是：读到 `if/else/for` 时，用**深度计数扫描**跳过或回退整块
   （`m80327J7` 前跳、`m80336K7` 回退到 for 头）。
3. `If` 的判定结果保存在 **If 动作对象自己的 boolean 字段**（`C20588c.f72913F`），
   `End If` 不存状态；`Else` 通过扫描回找最近的 If 头来读/写该字段。
4. 动作编号是硬编码 int（`if=37, else=43, endif=38, for=39, endfor=40, wait=30, wait until=35`），
   38/40 的注册表 flags 都带 `8`（块结束标记）。
5. 队列：默认上限 `mqt=10`（SharedPreferences），支持优先级 `ppi/ppri`、执行 id、局部变量包 `lvars`；
   同任务重入有"替换/拒绝"两种策略（`RejCopy`）。
6. 参数不是 JSON，而是 `Bundle`→XML 的 `argN` 子元素（`Str/Int/Bundle/Img/...`），
   变量 `%xxx` 在读取参数时展开，局部变量放任务自己的 Bundle。

---

## 1. 数据模型

### 1.1 磁盘格式（`.tsk.xml` / `.prj.xml`）

真实样本（Tasker 自己导出，`tv="6.7.7-rc"`）：

```xml
<TaskerData sr="" dvi="1" tv="6.7.7-rc">
  <Project sr="proj0" ve="2">
    <cdate>1792252629420</cdate><mdate>...</mdate>
    <id>26015562-...</id><name>ToolHub Tasker</name><tids>90201,90202</tids>
  </Project>
  <Task sr="task90201">
    <cdate>...</cdate><edate>...</edate>
    <id>90201</id><nme>ToolHub 启动</nme><pri>100</pri>
    <Action sr="act0" ve="7">
      <code>474</code>
      <Str sr="arg0" ve="3">...</Str>
      <Int sr="arg1" val="0"/>
    </Action>
  </Task>
</TaskerData>
```

解析/生成这两个方向的实现都在 `C21098ti`（原 `ti`）：

- 读：`m88054I(XmlPullParser, Bundle, String)`（`C21098ti.java:108`）
- 写：`m88058c(Appendable, Bundle, String, boolean, int)`（`C21098ti.java:269`）

写入器的元数据约定（决定了 XML 长什么样）：

| Bundle key | 含义 | XML 表现 |
| --- | --- | --- |
| `c.` | 容器标签名，缺省 `Vals` | `<Vals ...>` |
| `v.` | 版本号 | `ve="N"` |
| `t.` / `n.` / `a.` | 子元素额外属性名/值 | `<Str sr="arg0" ve="3">` 里的 `ve` |
| `r.` | 需要按字符串属性写出的 key 列表 | `sr="..."` 等 |
| `d.` | 直写文本的 key | `<tag>text</tag>` 而不是子元素 |

### 1.2 内存对象

| 角色 | 类 | 构造入口 |
| --- | --- | --- |
| 任务 | `C20813jn`（原 `jn`） | `C20813jn(C21098ti)` `C20813jn.java:1859` |
| 动作 | `C20588c`（原 `c`） | `C20588c(C21098ti)` `C20588c.java:1800` |
| 数据包 | `C21098ti`（原 `ti`） | Bundle 包装 + XML 读写 |
| 数据管理 | `C20843ko`（原 `ko`） | 任务/项目/场景的内存表 |
| 动作定义 | `C20561b1`（原 `b1`） | `(id,label,category,flags,name,Object... params)` |
| 动作总表 | `C20590c1`（原 `c1`） | 300 项静态定义 |

Task 构造读取的键（`C20813jn.java:1886-1943`）：

```
id(EXTRA_ID) rty pri lbl stayawake showinnot tmr rmid icn
handle timeout default back          // 场景模式
act0, act1, act2 ...                 // 动作数组，顺序即执行顺序
```

Action 构造读取的键（`C20588c.java:1826-1865`）：

```
code    动作编号
se      stop-on-error（默认 true）
on      启用开关（默认 true）
if      嵌套条件 Bundle（ve>=5 时；老版本从参数里拆）
lbl / labelC / coll / remoteDevice / remoteTimeout
argN    按动作定义的类型逐个解析
```

### 1.3 动作对象里的运行态

| 字段 | 含义 | 方法 |
| --- | --- | --- |
| `f72913F` | 该动作作为 If 头时的条件结果 | `m84999y()` 读 / `m84982q1(z)` 写（`C20588c.java:1703/1592`） |
| `f72929j` | 启用/禁用（`on`） | `m84924H()` |
| `f72925b` | 动作 code | `mo84037k()` |
| `f72927f` | 执行状态枚举 `b` | `m84919E1(b)` |

---

## 2. 动作注册表

`C20590c1` 里 300 个 `new C20561b1(...)`，格式：

```
C20561b1(action_id, label_res, category, flags, internal_name, params...)
```

`flags`：`&1` / `&2` / `&4` / `&8`（`&8` 出现在 `endif=38`、`endfor=40`、`anchor=300`）。
参数按 5 个一组解析（`C21245ym` 构造，`C21245ym.java:23`）：

```
(type, nameRes, default, required?, extra?)
type: 0/1=文本 2=数字 3=开关 4/5=特殊（图标/Bundle）
```

已导出：`Tasker动作注册表.tsv`（300 行、797 个参数，标签取自 `strings.xml`）。

控制流相关编号（同表可查）：

| code | internal_name | 标签 | flags |
| --- | --- | --- | --- |
| 37 | `if` | If | 0 |
| 43 | `else` | Else | 0 |
| 38 | `if` | End If | 8 |
| 39 | `for` | For | 0 |
| 40 | `for` | End For | 8 |
| `Constants.I2D` | `goto` | Goto | 4 |
| `Constants.L2F` | `stop` | Stop | 0 |
| 30 | `wait` | Wait | 4 |
| 35 | `wait_until` | Wait Until | 0 |
| 130 | `run_task` | Perform Task | 4 |
| 547 | `set_variable` | Variable Set | 4 |
| 129/131 | `javascriptlet`/`javascript` | 脚本 | 35 类目 |

> 注意：注册表里 `38`/`40` 的 `internal_name` 仍是 `if`/`for`（编辑器复用），
> 区分只能看 code 和 flags。

---

## 3. 执行管线

### 3.1 入口到入队

```
外部: net.dinglisch.android.tasker.ACTION_TASK
  → ReceiverStaticRunTasks (exported, PERMISSION_RUN_TASKS)
      putExtra("eventType", 9989) + extras
  → MonitorService.onStartCommand case 9989
  → ExecuteService.m80546k5(Bundle, boolean)   // 入队
```

`m80546k5`（`ExecuteService.java:14634`）做的检查，顺序即优先级：

1. `getBundle("mcro")` 为空 → 丢弃（`handleNewTask: null task bundle`）。
2. `flags & 1 == 0` 且 Tasker 被禁用 → `RejOff`。
3. 同任务已在队列且是 abort 型 → `RejCopy`（拒绝重复副本）。
4. 动作数 0 → `RejBad`；else/endif、for/endfor 不配对 → `RejBad`（`m86381p0`，见 §4.4）。
5. 队列 `size() >= mqt`（默认 10）→ `RejMaxQ`。
6. 分配 `execId`（自增）、`pid`（`gid` 或 execId）。
7. 按 flags 设置内部状态位（`&2→m86390s2`、`&4→m86404x2`、`&8→m86278B2`、`&16→m86321Q2`）。
8. `ppi` / `ppri` 覆盖优先级；`sc` 绑定场景；`lvars` 注入局部变量。
9. `%priority` 写入变量表；加入 `f70367M` 队列；`RunLog.Running`；启动工作线程。

`lvars` 特殊键（`ExecuteService.java:14730-14765`）：

```
return                  → 任务返回值变量
allow_child_ovvvreeeww  → 允许子任务覆盖
structure_return_var    → 结构化返回
其他 %xxx=字符串/整数    → 直接写入局部变量表
```

### 3.2 执行线程

`ExecuteService` 内部 `Runnable.run()`（`ExecuteService.java:824` 起）循环：

```
if (没有可运行任务) { 维护/退出检查; sleep 100~300ms; continue; }
else {
  %qtime = 排队耗时
  %tasker_current_action_number = 当前索引+1
  action = task.当前动作
  switch (step(task, action)) { ... }
}
```

### 3.3 单步执行

`m80331K2(Task, Action, int pos, int flag)`（`ExecuteService.java:7729`）是**块与分派的统一入口**：

```
code = action.code
if (!action.enabled) return -5                       // 禁用，跳过
if (动作是 blocking wait) → 走等待分支
if (code == 43 /*else*/ && if结果 == true)            // 真分支已执行过，跳过 else 块
    skipToEndOfBlock(task, 37, -1, 38) → -1
if (if结果 == false) {                                // 整个 if 块走 else/跳过
    code==37 → 记录 false，跳到 else 或 endif
    code==43 → 把 true 写回 if 头，跳到 endif
    code==39 → 跳过整个 for 块
    code==35 → 继续分派（wait until 自己会阻塞）
    其他     → -7 条件失败
} else {                                              // 条件成立
    code==40 → skipToStartOfBlock(39,40) → -2（回到 for 头再循环）
    code==37 → 记录 true，继续下一条
    code==43 → 跳过 else 块
    其他     → m80481d4(action, task, pos, flag)     // 真正的动作执行
}
```

`m80481d4`（`ExecuteService.java:12519`，JADX 未反编译，7,266 指令）按 `code` 分派到
各动作实现（HTTP、Shell、Intent、变量、脚本……），返回 `C21054s3`。

### 3.4 结果码

`C21054s3`（`C21054s3.java`）只有 4 个字段：状态码、消息、替换动作、子任务。

| 码 | 含义 | 状态机动作 |
| --- | --- | --- |
| `-1` | 继续下一条 | `task.m86291G1()` |
| `-2` | End For 命中，回到 For 头 | `m80336K7` 已回退 |
| `-3` | 动作失败 | 按 `se`（stop-on-error）决定是否 `Stop` 整任务 |
| `-5` | 动作被禁用，跳过 | 直接前进 |
| `-7` | 条件失败（If 为假） | 标记 `IfFail`，前进到 else/endif |

`%err`、`%errmsg` 由失败路径写入：`C20813jn.java:1011-1013`（`%err` = 数值），
`ExecuteService.java:13186`（异常消息）、`10686`（`timeout`）。

---

## 4. 控制流的具体实现

### 4.1 前跳：skipToEndOfBlock

`m80327J7(Task t, int startCode, int altCode, int endCode, boolean clearState)`（`ExecuteService.java:7619`）

```
depth = 1
while (true) {
    code = t.当前动作code()
    if (code == startCode) depth++
    else if (code == endCode) { if (--depth == 0) { t.前进(); break; } }
    else if (code == altCode && depth == 1) break      // 例如 else
    else if (code == -1) break                          // 到末尾
    t.前进()
    if (clearState) t.当前动作().清除状态()
}
```

用途：
- `If 为假`：`skip(37, 43, 38)` → 停在 `else` 或 `endif`。
- `Else 命中且 if 为真`：`skip(37, -1, 38)` → 停在 `endif`。
- `For 条件不成立`：`skip(39, -1, 40)` → 停在 `endfor`。

### 4.2 回退：skipToStartOfBlock

`m80336K7(Task t, int startCode, int endCode, boolean clearState)`（`ExecuteService.java:7924`）

```
depth = 1
while (true) {
    code = t.前一条动作code()
    if (code == endCode) depth++
    else if (code == startCode) { if (--depth == 0) { t.前进一步(); return; } }   // m86294H1
    else if (code == -1) return
    t.后退()
}
```

命中 `End For` 后回退到对应 `For` 头，返回 `-2`，下一轮 loop 从 For 头重新求值。

### 4.3 If/Else 的条件结果

- If 头执行时把结果写进**自己的** `f72913F`（`m84982q1`）。
- Else 分支：`m80646v5(task, pos)` 用 `task.m86276B0(pos,false,false,true)` 回找最近的 If 头，
  读它的 `f72913F`（`m84999y`）。
- 进入 else 时 `m80519h6(task, pos, true)` 把 If 头标记为已走真分支，防止重复。

所以 Tasker 的 If 状态**不是栈**，而是挂在块头动作上；嵌套靠"回找最近头"实现。

### 4.4 结构校验与自动补全

`C20813jn.m86381p0(boolean)`（`C20813jn.java:1561`）：

```
stack = []
for action in actions:
    code = action.code
    if (code == 39) push(40)          // For 期望 End For
    else if (code == 37) push(38)     // If 期望 End If
    else if (code == 38 || code == 40) {
        if (stack.top != code) return false   // 不配对
        pop()
    }
return stack.isEmpty()
```

入队时传 `true`：不配对时**自动补**缺失的 `End If`/`End For`（`m86347b0` 追加，`f73942h0++`）。
所以从外部塞进去的任务即使少写闭合动作也能跑，但结构错乱（如 if 里套 for 少闭合）会被判 `RejBad`。

### 4.5 其它控制动作

| 动作 | code | 实现要点 |
| --- | --- | --- |
| Wait | 30 | 阻塞当前执行线程，毫秒/秒/分/时/天 5 个参数；期间检查停止标志 |
| Wait Until | 35 | 条件为假时循环求值；`m80331K2` 里对 `code==35` 走"继续分派"而不是跳过 |
| Goto | `Constants.I2D` | 参数为动作序号/标签，直接改当前索引 |
| Stop | `Constants.L2F` | 参数 `with_error`、`task`（指定任务），置停止标志并写 `%err` |
| Perform Task | 130 | `m:1` 任务名、`priority`、`par1/par2`、`return`、`stop`、局部变量透传等 |

---

## 5. 变量与参数

### 5.1 作用域

- 全局：`SharedPreferences`（`C20586bq.m84875u0`）。
- 局部：任务自己的 `Bundle`（`task.m85649m()`），`lvars` 注入，`%caller1..N` 记录调用链
  （`C20586bq.m84872t0`，`C20586bq.java:3108`）。
- `%` 前缀常量：`C20586bq.f72890a`（`C20586bq.java:70`）。
- 结构变量：数组用 `%arr1/%arr2...` + `arr(#)` 形式（`array_*` 动作）。

### 5.2 参数读取与展开

每个参数读取都走 `C20588c.m84916D0(context, map)` / `m84918E0(context, map, boolean)`
（`C20588c.java:449/470`），`map` 是 `HashMap<String, List<String>>`，用于收集数组/列表值。

- 文本参数先按 `%` 展开（全局→局部→内置），再按参数类型解析（`uvar:`、`t:`、`m:`、`f:`、`js:` 等前缀由编辑器决定语义）。
- 未填参数不会报错，取默认值；`argN` 缺失时按 `ve` 做兼容补齐（`C20588c.java:1895+` 一长串老版本兼容）。

### 5.3 内置变量（执行器写入）

```
%err / %errmsg        动作失败
%priority             当前任务优先级
%qtime                排队耗时(ms)
%tasker_current_action_number   当前动作序号(1 基)
%caller1..%callerN    Perform Task 调用链
```

---

## 6. 外部协议与回调

- 触发：`ACTION_TASK` + extras（`task`/`par1`/`par2`/`priority`/`flags`/`lvars`/`gid`/`sc`）。
- 回调：`ACTION_TASK_COMPLETE`，data scheme `task`，路径为任务名；
  `TaskerIntent` 构造过滤器（`TaskerIntent.java:26`）。
- 权限：`net.dinglisch.android.tasker.PERMISSION_RUN_TASKS`（dangerous）。
- Manifest 中 `ReceiverStaticRunTasks` 声明在 `apktool/AndroidManifest.xml:739`。

---

## 7. 与 ShortX 的映射（落地建议）

| Tasker 概念 | ShortX 对应 | 迁移注意 |
| --- | --- | --- |
| Task = 动作数组 | 任务 = 动作链 | 顺序语义一致 |
| If/Else/End If | 条件动作 + 分支 | Tasker 用"块头存结果"，ShortX 用条件组件；嵌套深度要显式建模 |
| For/End For | 循环动作 | Tasker 回退到 For 头重新求值，ShortX 用循环变量 |
| Perform Task | 执行任务/子任务 | `%callerN`、返回值、局部变量透传要单独实现 |
| 变量 `%xxx` | 全局变量 + 局部变量 | 展开时机不同：Tasker 在参数读取时展开 |
| `se` stop-on-error | 失败即停 | 默认 true，与 ShortX 的失败策略对齐需显式设置 |
| `argN` Bundle | 参数对象 | Tasker 是 XML 多态元素，不是字符串 |

如果要**生成** Tasker 任务：按 `Tasker任务XML与参数编码.md` 的 XML 规则拼 `TaskerData/Task/Action`，
动作参数用对应类型元素（`Str`/`Int`/`Bundle`），`Action ve="7"`、`Str ve="3"`，
闭合动作（38/40）必须存在或至少结构配对，否则导入后行为不可预期。

---

## 8. 未验证 / 风险

1. `m80481d4` 未反编译：单动作内部实现（尤其 Shell/HTTP/Intent）没有逐个核对。
2. `m86381p0` 的自动补全只在入队时触发；手工 XML 导入是否走同一路径未验证。
3. `-3` 失败后的 `se` 处理链在 `run()` 里被 JADX 结构打散，只确认了 `%err` 写入点。
4. For 的 `%loop` 变量名/计数语义未在代码里定位到写入点（可能在 `m80481d4` 内）。
5. `goto` 的标签解析（按序号 vs 按 `lbl`）未逐行核对。
