# 方法钩子事件通道与安装（alpha20 / PR-4）

## 安装

- 目标应用必须在 LSPosed 作用域勾选；入口只对 `packageName` 匹配的普通应用进程安装，排除
  `system_server`（`android`）、`com.android.systemui` 和模块自身 `com.flowglass.demo`。
- classic 入口在 `handleLoadPackage` 中、modern101 在 `onPackageLoaded` 中先 hook
  `Application#attach(Context)`；拿到 Context 后按 `packageName + userId` 过滤配置，再在目标进程
  内解析类和方法。每个进程独立安装、独立限流、独立上报，状态键为
  `package@userId/processName`。
- 只安装用户约束收敛后的唯一重载；`NotFound` / `Ambiguous` 不安装，并把原因上报到设置页。
- 只读：`HookBridge.hookMethod` 的 after 回调只拿到参数副本和返回值；没有修改参数、替换返回值
  或跳过原方法的接口。回调异常在适配层和安装器两层吞掉，不外泄到目标应用。

## 通道

目标进程 `bindService` 到导出的 `MethodHookEventService`（无签名权限，因为目标应用不可能持有
我们的签名）。每个包：

1. 外层 `hookId` 仅用于选择 token；
2. 按精确 UTF-8 `body` 计算 HMAC-SHA256（每钩子 32 字节随机 token，只存 app-private prefs 与
   framework remote preferences）；
3. App 校验 `Binder.getCallingUid()`、调用用户、`getPackagesForUid` 是否包含目标包；
4. MAC 通过后才解析 body，再校验包名/用户/进程名、session 和严格递增 seq；
5. 时间戳窗口 ±5 分钟；新 session 必须比旧 session 更新，旧 session 重放会被拒绝。

`hello` 只建立 session；`install` / `rate` 上报安装或限流状态；`event` 携带固定中文内置变量
（`钩子应用`、`钩子用户`、`钩子进程`、`钩子方法`、`钩子签名`、`钩子标识`）和用户命名的提取项。
变量名必须在规则里声明过，未知变量直接拒绝；值截断到 512 字符。

## 信任边界

- 勾选作用域即表示信任目标应用。目标应用进程能读到自己配置里的 token；同 UID 的恶意代码也可能
  通过反射读取 token。本机制防的是**其他应用伪造和重放**，不是同 UID 内的密码学隔离。
- token 不进入 `method-hooks.json`、commands 导出、日志、广播或备份；但 classic
  `xposedsharedprefs` 和 modern remote preferences 本身是框架共享面。
- 目标进程在后台绑定服务可能被系统限制；失败时事件留在有界队列（最多 256 条），不会阻塞目标
  方法。跨用户/分身场景要求目标用户内也能访问本 App 的服务，当前不做跨用户绕过。
- 速率：每钩子 token bucket（默认 60/分钟、突发 10）在目标进程内先限流，App 侧再做状态/事件
  校验；超限只上报 `rate_limited`，不执行提取。

## 测试

- JVM：HMAC 篡改、调用方包/用户、重放与 seq、安装唯一/重载/缺失、限流、运行时 JSON。
- 不做模拟器测试、不截图；目标进程安装、Binder 绑定、ColorOS 14 后台限制需真机验证。
