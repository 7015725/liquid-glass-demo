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

1. App 先按 `Binder.getCallingUid()` 扣一次 UID 级入口预算（2400/分、突发 600），未知 hookId 也计入；
2. 解析外层包，用 `hookId` 选择 spec 与 token；
3. 校验调用 UID、调用用户、`getPackagesForUid` 是否包含目标包（这一步在扣钩子预算之前，外部应用
   无法用猜到的 hookId 挤掉目标应用的配额）；
4. 通过调用方校验后扣钩子级预算（`maxCallsPerMinute × 8` 个进程，默认 480/分）；超限是**终态拒绝**，
   客户端把包出队而不是保留队首重试；
5. 按精确 UTF-8 `body` 计算 HMAC-SHA256（每钩子 32 字节随机 token，只存 app-private prefs 与
   framework remote preferences）；
6. MAC 通过后才解析 body，再校验包名/用户/进程名、session 和严格递增 seq；
7. 时间戳窗口 ±5 分钟；新 session 必须比旧 session 更新，旧 session 重放会被拒绝。

`hello` 只建立 session；`install` / `rate` 上报安装或限流状态；`event` 携带固定中文内置变量
（`调用应用`、`调用用户`、`调用进程`、`调用方法`、`调用签名`）和用户命名的提取项；钩子标识来自事件里的
`hook.id`，由触发器的「调用标识」输出暴露，不是线上变量。
变量名必须在规则里声明过，未知变量直接拒绝；值截断到 512 字符。

## 订阅配置补发

`ACTION_CONFIG` 按内容 key 做两秒限流，避免订阅频繁变化时刷屏。连续 A→B→A 时，末态 A 与两秒前的 A
内容相同会被丢弃，持久化值是 A、钩子却停在 B。现在配置发送被限流时会按持久化的最新 mask/监听集合
安排一次补发（两秒后，仍走同一限流器）；`clearSubscriptions` 会取消待补发任务，不会在关闭后把旧配置
又送回去。

## 版本差异

`KeyguardController.setKeyguardShown` 在 Android 12 是 `(boolean, boolean)`，13–15 变成
`(int displayId, boolean, boolean)`；`MediaSessionService.onSessionPlaybackStateChanged` 在
AOSP 12–14 只有 `(record, boolean shouldUpdatePriority)`，没有 `PlaybackState`。钩子按类型定位
参数（第一个 Boolean、类型名以 `PlaybackState` 结尾的参数），不假设固定下标；找不到参数时放弃本次
事件而不是发错状态。

## 信任边界

- 勾选作用域即表示信任目标应用。目标应用进程能读到自己配置里的 token；同 UID 的恶意代码也可能
  通过反射读取 token。本机制防的是**其他应用伪造和重放**，不是同 UID 内的密码学隔离。
- token 不进入 `method-hooks.json`、commands 导出、日志、广播或备份；但 classic
  `xposedsharedprefs` 和 modern remote preferences 本身是框架共享面。
- 目标进程在后台绑定服务可能被系统限制；失败时事件留在有界队列（最多 256 条），不会阻塞目标
  方法。`bindService` 返回 false（而不是抛异常）也算未绑定，下一次入队会重试。
- 队列是每个进程一条、多个钩子共用：一条包最多在队首等 30 秒，超时丢弃；被丢弃的是 hello 时
  会重新武装，下一条事件再发一次 hello。这样单个永久失败的包不会把整个进程的通道堵死。
- 发送器本身的待执行队列也有界（256 个任务，满了丢最旧）：hook 回调不能阻塞目标线程，宿主不可用时
  也不能让待发送任务无限堆积。
- 事件里的变量上限按「5 个固定元数据 + 本钩子声明的提取项」计算，不再用固定 40；变量名在接收侧
  同样做 trim 归一化后再比对，带空格的声明名不会把合法事件判成未声明。
- 宿主 Service 重建（binder 断开/重连）时，目标进程清空已发 hello 的记录，下一条事件会重发 hello，
  避免会话表丢失后持续被拒；若该钩子一直没有新事件，则不会主动重发。跨用户/分身场景要求目标用户内也能访问本 App 的服务，当前不做跨用户绕过。
- 速率：每钩子 token bucket（默认 60/分钟、突发 10）在目标进程内先限流；App 侧入口还有两道：
  UID 级总量（2400/分、突发 600）与钩子级预算（默认 480/分，即目标侧上限 × 8 个进程）。任一道
  触发都只丢弃该包，不执行提取；目标进程把 `false` 当传输失败重试，所以宿主对终态拒绝返回 `true`，
  避免队首过期后卡死整个队列。

## 测试

- JVM：HMAC 篡改、调用方包/用户、重放与 seq、安装唯一/重载/缺失、限流、运行时 JSON。
- 不做模拟器测试、不截图；目标进程安装、Binder 绑定、ColorOS 14 后台限制需真机验证。
