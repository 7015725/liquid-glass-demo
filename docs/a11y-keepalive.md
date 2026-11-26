# 无障碍保活（多目标）

设置 → 无障碍管理：列出设备上已安装的无障碍服务（本应用固定置顶），勾选要保活的服务。
默认关闭；未开启总开关时不发送任何广播、不写任何系统设置。

## 后端与优先级

| 顺序 | 后端 | 前提 | 观察点 |
| --- | --- | --- | --- |
| 1 | LSPosed 钩子 | 模块在作用域内启用，且 `AccessibilityServiceConnection` 存在 | `binderDied` / `onServiceDisconnected` / `onServiceConnected` |
| 2 | Root 模块 | `/data/adb/modules/flowglass_a11y_keepalive` 已安装 | `service.sh` 每 30 秒巡检 |

「自动」= 钩子已加载且找到连接点时用钩子，否则已装 Root 模块时用模块，都没有则显示「未启用」。

## LSPosed 钩子

- 断线后在专用 `HandlerThread` 上调用框架自己的 `bindLocked()`，不自己构造 binder、不改服务列表。
- 同一组件串行重试，退避 500ms / 1.5s / 3s，10 分钟内最多 3 次；绑定后 1.5 秒验证 `mService != null`，成功即清零计数。
- 调用前持有连接对象的 `mLock`；框架仍认为 `mBound=true` 但 `mService==null` 时先 `unbindLocked()`。
- 每个回调和任务都由 `HookFailureGuard` 包裹，异常只进 LSPosed 日志，不向 system_server 抛出。
- 只观察连接生命周期，不进入只读事件总线（见 `lsposed-readonly-events.md`）。

## Root 模块（KernelSU / Magisk）

安装位置与运行时文件：

| 路径 | 作用 |
| --- | --- |
| `/data/adb/modules/flowglass_a11y_keepalive/module.prop` | 模块声明，KernelSU 与 Magisk 通用 |
| `/data/adb/modules/flowglass_a11y_keepalive/service.sh` | 开机由模块管理器执行 |
| `/data/adb/flowglass/a11y_keepalive.conf` | 勾选的服务，一行一个 `包名/类名` |
| `/data/adb/flowglass/a11y_keepalive.log` | 最近 200 行操作日志 |
| `/data/adb/flowglass/a11y_keepalive.pid` | 单实例锁 |

巡检逻辑：系统无障碍未开启则跳过；组件不在当前 `enabled_accessibility_services` 中则跳过
（用户移除后不加回）；`pidof` 仍能查到进程则跳过；`dumpsys package` 显示 `stopped=true` 则跳过；
同一组件 60 秒内只处理一次。重绑方式是从列表中移除再写回，失败则还原原始列表。

## 不会自动恢复的情况

- 总开关关闭，或该行未勾选。
- 系统无障碍总开关关闭。
- 组件已从系统设置的无障碍列表移除。
- 对应应用被强行停止（`FLAG_STOPPED` / `stopped=true`）。
- 10 分钟内已尝试 3 次（等待窗口过期后重新计数）。

## 验证

- 钩子加载：LSPosed 日志中出现 `a11y keepalive hooks=N`（N 为安装的检测点数量）。
- 页面状态卡：`钩子已加载` / `钩子已加载，但本 ROM 找不到连接点` / `钩子未加载`。
- 触发一次断线（系统设置里关闭再打开该服务），行状态依次为 `已断开` → `恢复中` → `已恢复`。
- Root 模块：`su -c 'tail -n 20 /data/adb/flowglass/a11y_keepalive.log'`，应看到 `started` 与 `rebind` 记录。

## 已知限制

- 钩子依赖 ROM 中的字段名 `mComponentName` / `mLock` / `mService` / `mBound`；字段缺失时该次重绑记为失败并受 3 次上限约束。
- Root 模块的 `pidof` 依赖 toybox/busybox，Android 10+ 自带。
- KernelSU 设备尚未实机验证；KernelSU 与 Magisk 都以 root 执行 `<module>/service.sh`，脚本按 POSIX 编写。
