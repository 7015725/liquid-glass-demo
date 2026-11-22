# 方法钩子：规格、解析与本地存储（alpha19 / PR-3）

本 PR 只落地可测试的纯逻辑，不在目标进程安装钩子，也不改触发器和设置页。安装、Binder 事件通道与触发器在后续 PR。

## 数据

- `MethodHookSpec`：目标 `packageName` + `userId`、`className`、`methodName`、可选 `paramCount` / `paramTypes`、提取项、每分钟上限与突发上限。
- `MethodExtraction`：用户命名的变量（允许中文），来源只能是 `ARGUMENT`（带参数序号）或 `RESULT`；`path` 是点分隔的字段路径，空字符串表示对象本身。
- 固定信息使用内置中文名：`调用应用`、`调用用户`、`调用进程`、`调用方法`、`调用签名`（钩子标识由触发器输出「调用标识」暴露）。用户提取项不能与它们、其他钩子的提取项，或已有局部/全局变量重名。
- 文件独立于 `commands.json`：`MethodHookFile(formatVersion=1)`。未知字段忽略，版本不符或 JSON 损坏时**失败关闭**，保留原文件并停止写入，不用空配置覆盖用户数据。
- 钩子 token 只存在 app-private `SharedPreferences`（`method_hook_secrets`），不进入 JSON、日志、备份或导出。PR-4 才把 token 交给目标进程做 HMAC。

## 解析

`MethodHookResolver` 只对目标类实际声明的方法做过滤：

1. 有 `paramCount` 时按参数个数过滤；
2. 有 `paramTypes` 时按全限定类型列表精确过滤；
3. 结果必须恰好 1 个，否则返回 `Ambiguous` / `NotFound`，不猜重载。

App 侧只校验语法；真正的解析必须在目标进程、类加载器就绪后执行，因为桌面 JVM 看不到目标应用的类。

## 提取

`MethodValueReader` 只允许字段路径，不执行方法或脚本；只对最终值调用 `toString()`，并截断到 512 字符。缺字段、null 中间值、访问失败、`toString()` 抛异常一律返回 null，不让回调异常穿透到目标应用。

## 测试

- engine：重载收敛、中文/重名/保留名、路径深度、参数数量/类型、限流参数、JSON 往返与版本拒绝。
- app：token bucket 突发/补充、字段路径/私有字段/异常 toString、独立文件往返、损坏文件保留、token 稳定与隔离。
- 按用户要求不做模拟器测试、不截图；真机安装与限流提示在 PR-4/5 验证。
