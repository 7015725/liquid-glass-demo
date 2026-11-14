# 任务 XML 与参数编码（6.7.7-rc）

写入器/解析器：`C21098ti`（原 `ti`）
- 写：`m88058c(Appendable, Bundle, String tag, boolean pretty, int depth)` — `C21098ti.java:269`
- 读：`m88054I(XmlPullParser, Bundle, String)` — `C21098ti.java:108`
- 转义：`& < >` 三个字符（`m88053G`，`:99`）

真实导出样本：`<TaskerData sr="" dvi="1" tv="6.7.7-rc">`，
Tasker 自己在导出时写 `tv`=版本名，`Action ve="7"`、`Str ve="3"`。

---

## 1. 顶层结构

```xml
<TaskerData sr="" dvi="1" tv="6.7.7-rc">
  <Project sr="proj0" ve="2">
    <cdate>毫秒</cdate><mdate>毫秒</mdate>
    <id>uuid</id><name>项目名</name>
    <tids>90201,90202</tids>          <!-- 项目内任务 id 列表 -->
  </Project>

  <Task sr="task90201">
    <cdate/><edate/>
    <id>90201</id>                    <!-- 数字，全局唯一 -->
    <nme>任务名</nme>
    <pri>100</pri>                    <!-- 0..100，默认 5 -->
    <Action sr="act0" ve="7"> ... </Action>
    <Action sr="act1" ve="7"> ... </Action>
  </Task>
</TaskerData>
```

## 2. Task 可选键（从 `C20813jn(C21098ti)` 反推，`C20813jn.java:1886-1943`）

| XML key | 类型 | 含义 |
| --- | --- | --- |
| `id` | Int | 任务 id（`C20537a6.EXTRA_ID="id"`） |
| `rty` | Int | 运行类型，默认 0 |
| `pri` | Int | 优先级，默认 5 |
| `lbl` | String | 标签 |
| `stayawake` | Bool | 执行时保持唤醒 |
| `showinnot` | Bool | 通知栏显示 |
| `tmr` | Bundle | 计时器配置 |
| `rmid` | String | 远程设备 id |
| `icn` | Bundle | 图标 |
| `handle` | Int | 场景模式（1 时读 `timeout`/`default`/`back`） |
| `act0..actN` | Bundle | 动作，必须连续编号 |

## 3. Action 键（`C20588c.java:1826-1865`）

| XML key | 类型 | 含义 |
| --- | --- | --- |
| `code` | Int | 动作编号（见 `Tasker动作注册表.tsv`） |
| `se` | Bool | stop-on-error，默认 true |
| `on` | Bool | 是否启用，默认 true |
| `if` | Bundle | 内联条件（ve≥5） |
| `lbl` | String | 标签（Goto 目标） |
| `labelC` | Int | 标签颜色 |
| `coll` | Bool | 折叠 |
| `remoteDevice` / `remoteTimeout` | String/Int | 远程执行 |
| `arg0..argN` | 任意 | 按动作定义的参数 |

## 4. 参数元素类型

写入器按**值的 Java 类**决定元素名，每个类型类都提供一个返回标签名的方法：

| 元素 | 类 | 例子 |
| --- | --- | --- |
| `Str` | `C20588c`（文本参数） | `<Str sr="arg0" ve="3">hello</Str>` |
| `Int` | `C20617d` | `<Int sr="arg1" val="0"/>` |
| `Bundle` | `C20674f` | 嵌套参数组 |
| `Img` | `C20703g` | 图标 |
| 其它 | 见 `C21098ti.f75018e` 数组（41 个类型标签） | `AppArg`、`Vals`、`Action`… |

`C21098ti.f75018e`（`:40`）就是全部标签名表，`f75019f` 是对应 id。
写入时 `bundle.get(str)` 的运行时类决定用哪个标签（`m88058c` 中的 `obj2.getClass()` 分支）。

### 4.1 属性元数据

写入器把某些特殊 key 从内容里"提出来"当属性：

- `bundle.getString("c.")` → 标签名（默认 `Vals`）
- `bundle.getInt("v.")` → `ve="N"`（>1 才写）
- `t.` / `n.` / `a.` → 子元素的额外属性名/值三元组
- `r.` → 需要按字符串属性写出的 key 数组
- `d.` → 直接写文本的 key（`<tag>value</tag>` 而不是子元素）

普通参数不需要这些；手工造 XML 时只需 `sr` + `ve`。

## 5. 条件（If）的 XML 形态

If 动作本身没有 `argN`，条件放在 `<ConditionList sr="if">` 里（`C20588c.java:1840`：
`ve >= 5` 时读 `getBundle("if")`）。

结构来自 `C21110u1`（条件组）与 `C21081t1`（单条件）：

- 组标签：`ConditionList`（`C21110u1.java:253`）
- 条件标签：`Condition`（`C21081t1.java:56`）
- 组内键：`c0, bool0, c1, bool1, ...`；`boolN` 是连接符，写在前一个条件之后（最后一个不写）
- 单条件键：`op`（int）、`lhs`（String）、`rhs`（String，可省略）

```xml
<Action sr="act0" ve="7">
  <code>37</code>
  <ConditionList sr="if">
    <Condition sr="c0" ve="3">
      <lhs>%TIME</lhs>
      <op>2</op>                       <!-- Matches -->
      <rhs>12:*</rhs>
    </Condition>
    <bool0>And</bool0>                 <!-- 仅多条件时出现；取枚举名 And/Or/Xor -->
    <Condition sr="c1" ve="3">
      <lhs>%BATT</lhs>
      <op>8</op>                       <!-- Equals（数值） -->
      <rhs>50</rhs>
    </Condition>
  </ConditionList>
</Action>
<Action sr="act1" ve="7"><code>43</code></Action>
<Action sr="act2" ve="7"><code>38</code></Action>
```

元素顺序与真实导出一致（`lhs` → `op` → `rhs`）；`ConditionList` 自身不写 `ve`，
`Condition` 写 `ve="3"`。可对照设备上的真实备份 `/sdcard/Tasker/configs/user/backup.xml`。

`op` 是 `Expr.EnumC20219Op` 的序号（`Expr.java:18`）：

```
0 EqualsString   1 NotEqualsString   2 Matches        3 NotMatches
4 MatchesRegex   5 NotMatchesRegex   6 LessThan       7 MoreThan
8 Equals         9 NotEquals        10 Even          11 Odd
12 Set          13 NotSet
```

`Even/Odd/Set/NotSet` 不需要右值（`hasRightSide=false`）。

老版本兼容（`C21081t1.java:163`）：
`ve < 2` 时 `op--`；`ve < 3` 时按 `m87992a` 映射到新序号。
所以手工造 XML 时把 `<Condition ve="3">` 和 `Action ve="7"` 一起写，能绕开这些兼容分支。


## 6. 生成任务的最小检查清单

1. `Task.id` 数字唯一；`Project.tids` 与之一致。
2. `Action` 的 `code` 必须在注册表里存在（否则 `C20588c` 会改成 300 并标 "Missing action"）。
3. `code=37` 必须有配对的 `38`，`39` 配 `40`；否则导入/入队会 `RejBad` 或自动补全。
4. 文本参数写 `Str`，数字写 `Int`，不要全部塞 `Str`（类型不符会在参数解析时走默认值）。
5. `ve` 不要乱写：`Action ve="7"`、`Str ve="3"` 是 6.7.7 的当前值；低版本号会触发兼容分支。
6. 变量 `%xxx` 直接写在文本里，运行时展开；需要字面 `%` 时按 Tasker 规则转义（`%%`）。
