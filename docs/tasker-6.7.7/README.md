# Tasker 6.7.7-rc 逆向资料索引

本目录是外部静态逆向成果的**参考资料**，不是本仓库的实现代码，也不是本仓库产出的验证结论。
四份文件由用户于 2026-11-14 提供，内容对应 `net.dinglisch.android.taskerm` 6.7.7-rc
（versionCode 5453，minSdk 27 / targetSdk 36，APK SHA-256 `1afc60d0…0b389`）。

## 文件

| 文件 | 行数 | 内容 | 原始 SHA-256 |
| --- | --- | --- | --- |
| `Tasker动作注册表.tsv` | 301（300 动作 + 表头） | 动作 id / 内部名 / 标签 / 类目 / flags / 参数串 | `3c8fe53663e82f6568bcc348ece243c4a81abeac558495eb84b6552256ca3d9f` |
| `Tasker任务执行实现方案.md` | 390 | 数据模型、注册表、执行管线、控制流、变量、外部协议 | `8664a1112f578d84bdf7edba5617128e5d0c9889c73eb38a89d4bf1c2204f1d1` |
| `Tasker任务流程与参数传递.md` | 357 | 生命周期、入队、结果码、任务间衔接、参数与变量传递 | `b7fc6a0c8e4781141555cc30fc1d78b22141eb86fff76fb9b2f975aeb7c680c4` |
| `Tasker任务XML与参数编码.md` | 150 | `.tsk.xml` 顶层结构、Task/Action 键、参数元素类型、If 条件 XML | `9282fbd08047a18fa67ec24536ffedbe0fbe2d88a63602f737dbcb59b7847c9b` |

文件按原样保存（文件名、字节均未改），上表哈希与来源文件一致。

## 一句话结论

- 执行模型：**动作数组 + 单线程索引状态机**，不是跳转表；`If/Else/For` 靠代码 37/43/38/39/40 的深度计数扫描实现块跳转。
- 状态存放：`If` 的判定结果挂在**该 If 动作对象自己的 boolean 字段**，`Else` 回找最近 `If` 头读取；不是栈。
- 队列：默认上限 10（`mqt`），入队即校验（空 `mcro`、禁用、重入、结构不配对、超限），失败只记 `Rej*` 不执行。
- 参数：不是 JSON，是 `Bundle` → XML 的 `argN` 元素（`Str` / `Int` / `Bundle` / `Img` …），`%变量` 在**读取参数时**展开。
- 任务衔接：`Perform Task(130)` 把子任务序列化 + `lvars` 作为一个新的执行请求投递；`%par1/%par2` 只有两个位置，更多走「局部变量直通」。
- 注册表：300 个动作、797 个参数定义；`38`/`40` 的内部名仍是 `if`/`for`，只能靠 code + flags(`&8`) 区分。

## 关键事实速查

### 结果码（`ExecuteService` 单步返回）

| 码 | 含义 | 后续 |
| --- | --- | --- |
| `-1` | 继续 | 索引 +1 |
| `-2` | `End For` 命中 | 回退到 `For` 头重新求值 |
| `-3` | 动作失败 | 按 `se`（默认 true）决定是否停止任务 |
| `-5` | 动作被禁用 | 跳过 |
| `-7` | `If` 条件为假 | 前进到 `Else` / `End If` |

### 控制流动作

| code | 内部名 | 标签 | flags |
| --- | --- | --- | --- |
| 37 | `if` | If | 0 |
| 43 | `else` | Else | 0 |
| 38 | `if` | End If | 8 |
| 39 | `for` | For | 0 |
| 40 | `for` | End For | 8 |
| 30 | `wait` | Wait | 4 |
| 35 | `wait_until` | Wait Until | 0 |
| 130 | `run_task` | Perform Task | 4 |
| 547 | `set_variable` | Variable Set | 4 |
| 123 | `run_shell` | Run Shell | 4 |

### XML 骨架

```xml
<TaskerData sr="" dvi="1" tv="6.7.7-rc">
  <Task sr="task90201">
    <id>90201</id><nme>任务名</nme><pri>100</pri>
    <Action sr="act0" ve="7"><code>123</code>
      <Str sr="arg0" ve="3">ls /sdcard</Str>
      <Int sr="arg1" val="30"/>
    </Action>
  </Task>
</TaskerData>
```

`Action ve="7"`、`Str ve="3"`、`Condition ve="3"` 是 6.7.7 当前值；写低版本号会触发兼容分支。
`If` 的条件放在 `<ConditionList sr="if">` 里，`op` 是 14 项枚举（0 EqualsString … 13 NotSet）。

### 变量作用域

| 类型 | 命名 | 存储 | 生命周期 |
| --- | --- | --- | --- |
| 全局 | 含大写 `%MyVar` | SharedPreferences | 跨任务、跨重启 |
| 局部 | 全小写 `%myvar` | 任务 Bundle | 本次运行 |
| 参数 | `%par1` / `%par2` | 子任务 lvars | 子任务运行 |
| 调用链 | `%caller1..N` | 子任务 lvars | 子任务运行 |
| 返回 | `%return` | 子任务 lvars → 父任务变量 | 由父任务读取 |

未定义变量**不报错**，`%foo` 原样保留；跨任务必须显式传参、直通或写全局。

## 校验与边界（继承原文）

- 纯静态读取：未启动应用、未触发任务、未改数据；**没有动态验证**。
- JADX 以 312 个方法级错误结束（exit 3）；最关键的 `ExecuteService.m80481d4`（7,266 指令）未反编译，
  单动作内部实现（Shell/HTTP/Intent 等）没有逐个核对。
- 原文标「推断」的项：Perform Task 的停止/直通限制/允许覆写/重置返回在分派器内的实现、父任务是否阻塞等待、
  `bosta` 结构化返回的解析细节、`For` 的循环计数语义、`goto` 标签解析。
- 注册表 300 行里 13 行是 `deprecated`，40 行 `internal_name` 为 `null`（多为系统设置页的 dialog 动作）。
- 若要验证上述推断，只能动态跑：两条最小任务 + `%par1`/`%return`/`%caller1`，看 RunLog。

## 与本仓库的关系

目前**只归档，不接入**。如果后续要做 Tasker 兼容（导入/导出），这份资料能直接支撑：

1. 导入器：解析 `TaskerData/Task/Action`，按 `code` 查注册表，把 `argN` 按参数类型还原；
   校验 `If/End If`、`For/End For` 配对（不配对时按 `m86381p0` 语义拒绝或补全）。
2. 动作映射：本仓库的 `BuiltInComponents` 是稳定 `typeId` 的 typed schema，与 Tasker 的 `code + argN`
   需要显式映射表；Tasker 的 `%变量` 与本仓库 params 的差异要先决定（保存原文 or 求值）。
3. 导出器：按 XML 规则拼 `Action ve="7"` / `Str ve="3"`，闭合动作必须存在。

注意：Tasker 参数在读取时展开变量，而本仓库的 params 是持久化规范值；两者语义不同，不能直接互换。
