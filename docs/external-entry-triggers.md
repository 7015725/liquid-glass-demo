# 外部入口触发器

本页说明通过显式 Intent、本机 HTTP、快速设置磁贴和系统分享进入规则的四类触发器。它们都是**事件型**触发器：ALL 模式只判断窗口内是否发生过，不把“当前是否仍有请求”当作状态。

## 总览

| 触发器 | typeId | 入口 | 默认监听 |
| --- | --- | --- | --- |
| 收到 Intent | `trigger.intent_received` | 显式广播 `IntentTriggerReceiver` | 无隐式 action |
| HTTP 请求 | `trigger.http_request` | 本机 HTTP 服务 | `127.0.0.1:8765` |
| 快速设置点击 | `trigger.quick_setting` | 用户点击磁贴 | 需手动添加磁贴 |
| 接收到分享 | `trigger.share_received` | 系统分享 `ACTION_SEND` | 文本、图片 |

四类入口都只生成一次事件；规则的全局 2 秒防抖仍然生效。

## 收到 Intent

调用方必须把广播**显式发送给**：

```text
com.flowglass.demo/.system.trigger.IntentTriggerReceiver
```

接收器不声明隐式 action 过滤器，因此普通系统广播不会进入触发器引擎。每条规则必须设置自己的令牌，调用方在 `flowglass_token`（或兼容的 `token`）额外参数中携带同一令牌：

```bash
adb shell am broadcast \
  -n com.flowglass.demo/.system.trigger.IntentTriggerReceiver \
  --es flowglass_token 'rule-token' \
  --es demo 'hello'
```

- 令牌为空或与规则不一致时不触发。
- 可选 `action` 参数用于精确匹配 Intent action；留空匹配任意 action。
- 只转发字符串、整数、长整数、布尔、浮点和双精度额外参数。
- 最多 32 个额外参数，序列化后最多 4 KiB；单个值最多 512 字符。
- 不转发 Parcelable、文件描述符或嵌套 Intent。
- 输出：`Intent动作`、`Intent数据`、`额外参数`（默认名，可在规则里改名）。
- 如果触发服务尚未运行，接收器会尝试以前台服务启动它；系统拒绝后台启动时本次事件会被丢弃，不会伪造成功。强行停止应用后，广播也不会投递，直到用户再次打开应用。

## HTTP 请求

触发服务在规则指定的端口启动一个只面向本机的小型 HTTP 监听器：

```bash
curl -i -X POST \
  -H 'X-Flowglass-Token: rule-token' \
  --data 'hello' \
  'http://127.0.0.1:8765/hook'
```

- 默认绑定 `127.0.0.1`；选择“局域网”后才绑定 `0.0.0.0`。
- 默认端口 `8765`，可在规则里改成 `1024–65535`。
- 令牌可放在 `X-Flowglass-Token`、`Authorization: Bearer` 或 `token` 查询参数中；规则令牌为空时不会启动服务。
- 方法支持 `GET`、`POST`、`PUT`；请求体最大 64 KiB；每令牌每分钟最多 60 次请求。
- 成功返回 `202 Accepted`；令牌错误 `401`；方法不支持 `405`；请求过大或格式错误 `400`；超频 `429`；监听器已停止 `503`。
- 只处理单次请求/连接，不支持 keep-alive、分块传输或 TLS。
- 输出：`方法`、`路径`、`查询串`、`正文`、`HTTP来源`。
- 端口被占用、绑定失败或 Android 17+ 未授予本地网络权限时，规则不会启动监听；日志中会记录原因。

HTTP 入口默认只允许本机访问，不建议在没有额外网络隔离的情况下选择“局域网”。

## 快速设置点击

添加“流光”快速设置磁贴后，点击磁贴会触发所有启用的 `trigger.quick_setting` 规则。磁贴由用户手动添加到快捷设置面板；应用不会修改或读取其他磁贴。磁贴点击是用户操作，不携带请求正文或额外参数。

## 接收到分享

从其他应用使用系统分享并选择“流光”时触发。支持 `text/plain` 和 `image/*`：

- 文本放在 `Intent.EXTRA_TEXT`，输出为 `分享文本`。
- 单个文件 URI 放在 `Intent.EXTRA_STREAM`，输出为 `分享文件URI`。
- MIME 类型输出为 `分享类型`。
- 规则可按“任意 / 文本 / 图片”过滤。
- 分享 Activity 只读取上述字段，立即结束，不申请持久 URI 权限，也不复制文件内容。

## 权限与后台边界

- Intent、快速设置和分享入口不新增运行时权限。
- HTTP 的局域网绑定在 Android 17+ 需要本地网络权限；仅本机回环不需要网络暴露。
- 所有入口都受 Android 后台启动限制；服务被系统停止或用户强行停止后，不能保证接收外部事件。
- 应用没有常驻的隐式广播监听；Intent 入口必须显式指向接收器组件。
