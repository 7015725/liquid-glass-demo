# 系统广播触发器（M1）

本文描述第一批系统事件触发器：全部由应用进程内的动态接收器、`ContentObserver` 和轻量调度器
产生，**不新增任何权限**，也不依赖 Root / LSPosed。

## 触发类型

| typeId | 名称 | 事件参数 | 输出 |
|---|---|---|---|
| `trigger.system_time_changed` | 系统时间/时区变更 | `event`：`any` / `date` / `time` / `timezone` | `trigger.system_time.kind` |
| `trigger.next_alarm_changed` | 下一个闹钟变化 | `package`：可选包名精确匹配 | `trigger.alarm.time`、`trigger.alarm.package` |
| `trigger.storage_media` | 存储卡挂载/卸载 | `event`：`any` / `mounted` / `unmounted` / `removed` | `trigger.storage.path`、`trigger.storage.event` |
| `trigger.battery_level` | 电量 | `direction=any` 新增「任意变化」 | `trigger.battery.level` |
| `trigger.battery_full` | 电池充满 | 无 | `trigger.battery.level` |
| `trigger.battery_overheat` | 电池过热 | `temperature`：30–80 °C | `trigger.battery.temperature` |
| `trigger.shutdown` | 设备关闭 | 无 | 主输出（触发时间） |
| `trigger.storage_low` | 存储空间不足 | `threshold`：0–100% | `trigger.storage.freePercent` |
| `trigger.locale_changed` | 区域设置变更 | 无 | `trigger.locale.tag` |
| `trigger.package_change` | 应用安装/卸载/更新 | `event`：`any` / `added` / `removed` / `replaced` | `trigger.package.name`、`trigger.package.label`、`trigger.package.event` |
| `trigger.wallpaper_changed` | 壁纸已更改 | 无 | 主输出（触发时间） |
| `trigger.setting_changed` | 系统设置变更 | `namespace`：`system` / `secure` / `global`；`key` | `trigger.setting.namespace/key/value` |
| `trigger.tick` | 周期滴答 | `interval`：5–86400 秒 | `trigger.tick.interval` |
| `trigger.accessibility_changed` | 无障碍服务变更 | 无 | `trigger.accessibility.count`、`trigger.accessibility.services` |

## 匹配语义

- 这些触发器都是**事件型**：只表示“发生过”，不代表“当前处于某状态”。
- `ALL` 模式要求窗口内每个事件型触发器都至少命中一次；窗口默认 60 秒。
- 规则级 2 秒防抖仍是硬下限。`trigger.tick` 的实际间隔不会小于 5 秒，且会被防抖拉长到至少 2 秒。
- 事件族（`system_time_changed`、`storage_media`、`package_change`）用同一个 typeId，靠 `event`
  参数区分；`any` 表示不过滤。

## 可靠性说明

| 触发器 | 说明 |
|---|---|
| 系统时间 / 时区 | `ACTION_DATE_CHANGED` 每天零点也会触发；手动改时间、自动对时都可能触发。 |
| 下一个闹钟 | 只读取系统的下一个闹钟；取消闹钟时输出为空。部分时钟应用只更新自己的闹钟，不更新系统闹钟。 |
| 存储卡 | 只针对可移动存储；内置存储不会发这些广播，多数现代手机也没有可移动存储。 |
| 电池充满 / 过热 | 由 `ACTION_BATTERY_CHANGED` 驱动，只在“未充满 → 充满”或“低于阈值 → 达到阈值”的边沿触发。已处于该状态时启动自动化不会补触发。部分设备不报告温度。 |
| 设备关闭 | 系统开始关机时触发，可执行时间很短；长任务、网络请求很可能来不及完成。 |
| 存储空间不足 | 系统低存储广播 + 每 60 秒一次 `StatFs` 检查；低于阈值期间最多每 30 分钟触发一次。 |
| 区域设置 | 语言、区域变化时触发；不是时区变化（时区属于 `system_time_changed`）。 |
| 应用安装/卸载/更新 | 更新只触发一次（只处理 `PACKAGE_REPLACED`，忽略带 `EXTRA_REPLACING` 的卸载/安装）。应用名称受包可见性限制，读不到时为空。 |
| 壁纸 | 使用已弃用但仍在发送的 `ACTION_WALLPAPER_CHANGED`；个别 ROM 可能不发。 |
| 系统设置 | `ContentObserver` 监听整个命名空间，按命名空间 + 键名精确匹配；高频设置（如亮度）会受 2 秒防抖。读取失败时值为空。 |
| 周期滴答 | 不是精确闹钟；息屏、Doze、后台限制都可能延后。应用被强行停止后停止，重新打开后恢复。 |
| 无障碍服务 | 监听 `enabled_accessibility_services`；服务列表以逗号分隔写入 `trigger.accessibility.services`。 |

所有监听器都随触发器前台服务存活。应用被强行停止后，Android 不会向它投递任何广播，直到用户
再次打开应用。

## 测试

```bash
# 单元测试
./gradlew :engine:testAndroidHostTest :androidApp:testDebugUnitTest --no-configuration-cache

# 真机：改一个系统设置，观察规则日志
adb shell settings put system screen_brightness 80

# 真机：安装/卸载一个测试包，观察 package_change
adb install -r app-debug.apk
adb uninstall com.example.test
```

时间、时区、关机、低存储等系统保护广播无法由普通应用伪造，需要真机手动触发或使用系统镜像。
