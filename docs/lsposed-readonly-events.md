# LSPosed 只读事件（alpha7）

本阶段只增加系统框架侧的只读观察，不修改任何系统方法、参数或返回值。所有能力默认关闭，
进程/崩溃事件只在至少一条启用规则订阅时才采集，异常只写入 LSPosed 日志，不进入
system_server。

## 新增组件

| 组件 | 作用 | 执行层 |
| --- | --- | --- |
| `trigger.app_process` | 应用进程启动 / 结束，参数支持 `package@userId` | LSPosed |
| `trigger.app_crash` | 应用崩溃 / ANR；只发送事件类型，不读取堆栈或异常文本 | LSPosed |
| `trigger.keyguard` | 锁屏 / 解锁；只观察 `setKeyguardShown` 的状态边沿 | LSPosed |
| `trigger.media` | 媒体播放 / 暂停 / 停止；只观察媒体会话状态，不控制播放 | LSPosed |
| `trigger.foreground_service` | 前台服务进入 / 退出；只观察状态，不读取通知内容 | LSPosed |
| `condition.app_process_state` | 读取 App 侧最近一次进程事件缓存；缓存 120 秒过期后按不满足处理 | 普通 |

`trigger.app_foreground` 仍保留普通、Root、LSPosed 三种执行方式。进程、崩溃/ANR、锁屏、媒体和前台服务触发器有意只声明
LSPosed 层，不提供假的普通回退；没有系统钩子时规则显示「等待系统钩子」。

## 订阅与隐私

- 总开关默认关闭；关闭时钩子不发送任何事件。
- `TriggerHost` 只把启用规则实际使用的族写入配置：
  - 前台规则：`FOREGROUND`
  - 进程规则：`PROCESS`
  - 崩溃 / ANR 规则：`CRASH`
  - 锁屏规则：`KEYGUARD`
  - 媒体规则：`MEDIA`
  - 前台服务规则：`FOREGROUND_SERVICE`
- 进程和崩溃事件额外携带启用规则中的应用 `package` / `package@userId` 白名单。钩子在
  system_server 内先按白名单过滤，不匹配的进程不进入队列。
- 广播只包含包名、userId、事件类型、时间戳和序号，不含标题、正文、异常堆栈、ANR reason
  或其他系统对象。
- 广播使用显式组件和 signature 权限；App 丢弃未知事件、非法包名、非法 userId、过期状态。

## 线程与失败处理

- 钩子回调只复制 allowlist 中的原始值，并按事件族放入有界队列；队列满时丢最旧事件。
- 真正的广播在独立 `HandlerThread` 上发送，避免在 AMS/WMS 锁内调用系统服务。
- 每个 hook 回调和 post 出去的 Runnable 都由 `HookFailureGuard` 包裹；任何 ROM 差异只记录
  日志并跳过该事件。
- 进程事件 750ms 去重；进程结束的 `restarting=true` 视为重启，不发送「结束」。
- 锁屏只在 `setKeyguardShown` 的布尔值变化时发送；`keyguardGoingAway` 不当作已解锁。
- 媒体只发送播放 / 暂停 / 停止三个状态；同一媒体会话只有状态变化时才发送，忽略缓冲和错误等中间态。
- 前台服务只在进入 / 退出前台状态时发送；按 `package@userId` 去重，不读取通知标题或正文。
- 状态握手沿用 alpha6：钩子发 `ACTION_LOADED`，App 回发开关、订阅和
  `app_knows_loaded=true`；心跳携带各族已安装的检测点数量。

## 已验证范围

- 单元测试：事件模型、触发器匹配、条件失败关闭、订阅过滤、有界队列、异常隔离和广播内容。
- 真机验证以用户设备为准；本 PR 不修改模拟器，也不附带截图。
- `ActivityManagerService#startProcessLocked`、`#cleanUpApplicationRecordLocked`、
  `#handleApplicationCrashInner` 和 `AnrHelper#appNotResponding` 的实际重载因 ROM 而异，需在
  ColorOS 14 / Android 14 真机确认。
