# 流光自动化 · 指令编写版

这是基于 `7015725/liquid-glass-demo` 原生 Kotlin / Jetpack Compose UI 实现的规则编写器，沿用液态玻璃导航、卡片及控件，不是 WebView 包装。

## 可以做什么

- 新建、重编辑、重命名、置顶、复制、删除和撤销删除自动指令 / 一键指令。
- 配置触发器、条件、动作；按名称或 typeId 搜索组件，按照元数据填写参数。
- 设置 any / all / custom 匹配、自定义至少 N 项及 AND / OR / NOT 逻辑树；引用绑定组件 ID，排序不改变引用。
- 设置重复触发策略、防抖、动作超时、失败策略、重试次数；动作支持排序、复制及嵌套 IF / THEN / ELSE、循环。
- 管理字符串、Long 整数、小数、布尔、列表、字典、null 变量；局部变量与全局变量独立存储，提供只读内部变量说明。
- Room 真实落盘；保存失败保留规则草稿。规则编辑器支持配置重建和放弃修改确认。
- 从系统文件选择器导入版本化 JSON，完整预览、校验后确认；涉及脚本时另行确认。导入新建规则 ID、全部停用，全局变量重名拒绝整批导入。
- 导出单条 / 全部规则及全局变量，写入后回读核验。文件限制为 1 MiB，未知版本或非法内容不静默忽略。

## 不包含什么

此版**只编写、校验、保存，不监听事件或执行规则**。所有规则保持停用；不会把保存结果展示成“执行成功”。组件目录中的 Root、Shizuku、LSPosed、Shell、JavaScript 等是能力与参数声明，不代表对应执行器已接入。

保留原仓库的保活、Root 和权限设置页，供后续执行端接入使用；它们不能让此版已保存的规则自动运行。LSPosed 入口仅显示“未接入”说明，不提供激活操作。不要为编写、保存或导入规则授予 Root。JSON 包含变量值及脚本文本，只向可信对象分享。

## 快速使用

1. 概览点击“新建自动指令”或“新建一键指令”，也可显式选择一个模板。
2. 填写名称，在对应分区添加组件并完成参数表单；自动指令至少需要一个触发器，两类指令都需要动作。
3. 点击“保存”。通过校验并写入成功后返回原列表，再点击卡片或“编辑”重新打开。
4. 长按卡片使用置顶、重命名、导出、复制或删除菜单。
5. 在概览、指令列表或设置的“规则资料库”管理全局变量和 JSON 文件。

空库不会自动填充样例。模板只有用户点击并保存后才会进入数据库。

## 本地构建

已使用 JDK 21、Gradle Wrapper 9.7.1、AGP 9.3.2、Kotlin 2.4.10、Android SDK / Build Tools 37 构建。依赖版本固定在 `gradle/libs.versions.toml`；不要因本地记忆较旧而擅自降级。Gradle 代理只保留在用户环境，不写入工程。

```bash
./gradlew :engine:test :androidApp:testDebugUnitTest \
  :androidApp:assembleDebug :androidApp:lintDebug \
  --no-configuration-cache --max-workers=2 --console=plain
```

- Debug 包名：`com.flowglass.demo.authoring`，可与原 Demo 并存。
- 版本：`0.4.0-authoring`，versionCode 4。
- minSdk 26，targetSdk / compileSdk 37。
- 固定构建产物：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。
- Debug 使用调试签名。未配置发布密钥，不将 unsigned Release 冒充可发布包。

## 代码位置与验证边界

- `engine/`：纯 Kotlin 模型、组件元数据、校验、JSON 协议及测试。
- `androidApp/.../data/`：Room 表、事务与仓库。
- `androidApp/.../ui/editor/`：规则、参数、逻辑树和变量编辑器。
- `App.kt` / `Model.kt` / `AuthoringOverlays.kt`：导航、ViewModel、导入导出与反馈。

测试包括真实 Compose 触摸、窄屏长列表、主界面新建至保存再打开、无障碍点击语义、非法参数、Room 关闭后重读、导入事务回滚及脚本确认。Robolectric / Native Graphics 的测试与截图不是设备运行证据。没有连接真机或模拟器，尚未完成安装、系统文件提供器互操作、键盘与权限流程的设备验收。

本机最终验收为 122 项测试通过；APK 构建、签名与完整性检查通过，Lint 为零错误、15 项警告，累计修复已通过独立复审。具体源码身份、测试证据和 APK 哈希随本地交付报告提供。仓库同步及合并状态以分支和 Pull Request 为准，本地 Debug 包不等同已发布 Release。
