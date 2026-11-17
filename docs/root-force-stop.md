# Root 强制停止动作

`action.force_stop` 是本项目唯一的 Root 写动作，不经过 LSPosed。

## 输入与校验

- 参数只接受一个应用：`package` 或 `package@userId`。
- 包名必须匹配 `[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*`，不允许空格、分号、重定向等 Shell 字符。
- `userId` 必须是 `0–999999` 的整数；省略时为 `0`。
- 拒绝 `android`、`com.android.systemui`、本应用 `com.flowglass.demo`，以及系统查询到的所有桌面启动器包名。
- 校验在 App 进程内完成；命令固定为 `am force-stop --user <userId> <package>`，不做字符串拼接以外的 Shell 解析。

## 执行与失败

- 使用现有 Root 会话执行；超时 30 秒，输出有上限。
- 超时、输出截断或退出码非 0 时只返回失败，不重试。
- 成功只记录「已强制停止：package@userId」，不读取或记录被停止应用的界面内容。
- 没有 Root 授权时该动作在图层选择阶段不可用，不会退回普通执行。
