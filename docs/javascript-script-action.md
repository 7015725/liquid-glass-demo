# JavaScript 脚本动作（Rhino 沙箱）

`action.script` 使用 `org.mozilla:rhino:1.9.2`。脚本在后台线程执行，结果写入
`%{script.result}`；执行成功、失败和取消都会写执行日志。

## 编辑器

参数页有两个字段：

- `脚本`：多行 JavaScript。
- `超时`：整数秒，默认 10，范围 1–60。

参数页的提示里提供最小示例和 API 清单。

## API

| 调用 | 说明 |
| --- | --- |
| `get(name)` | 读取变量。`run.` / `rule.` / `global.` 前缀可指定作用域；裸名读取运行级。 |
| `set(name, value)` | 写入变量，走与「设置变量」动作相同的校验和持久化路径。 |
| `log(text)` | 写入执行日志，最多保留 4000 字符。 |
| `toast(text)` | 在主线程显示短 Toast。 |
| `notify(title, text)` | 发送系统通知；Android 13+ 需要通知权限，否则报错。 |
| `http(method, url, body?)` | 受控 HTTP，仅 GET/POST、仅 http(s)，8 秒超时；响应过长时由 HTTP 层截断。 |
| `sleep(ms)` | 最多 5 秒且受整体超时约束，可被停止按钮中断。 |

示例：

```js
log("开始");
var count = Number(get("run.count") || 0) + 1;
set("run.count", count);
set("global.lastScript", "ok");
"第 " + count + " 次";
```

## 安全边界

- 使用 `initSafeStandardObjects()`，不注册 Java 包访问。
- `ClassShutter` 白名单为空，脚本无法访问 `java.*`、反射、文件、进程或任意类。
- 解释执行（`optimizationLevel = -1`），不生成 JVM 字节码。
- 脚本长度上限 64 KiB，解释器栈深 256，指令数保护 2,000,000，结果/日志各截断到 4000 字符。
- 默认 10 秒、可配置 1–60 秒的墙钟超时；`while(true)` 等死循环由指令观察器中断。
- 动作运行在执行 Job 中，停止按钮取消 Job 并中断脚本线程。
- 导入预览把 `action.script` 与 Root Shell、HTTP、执行另一条指令一起列为敏感动作。
