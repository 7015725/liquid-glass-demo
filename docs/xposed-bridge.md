# Xposed 入口适配层（alpha17）

系统钩子和后续方法钩子不再直接依赖某一个 Xposed 框架 API。入口层只负责把框架回调翻译成
统一的进程描述和 `HookBridge`，核心层只依赖这个窄接口。

## 入口

- `XposedEntry`：保留的 classic `de.robv` 入口，继续由 `assets/xposed_init` 加载。
- API101 入口：后续 PR 增加 `META-INF/xposed/java_init.list`、`module.prop` 和
  `onModuleLoaded` / `onSystemServerStarting` / `onPackageReady`。
- 不增加 modern API100 入口；旧框架统一走 classic 入口。

`module.prop` 的 `targetApiVersion` 是单值，modern API100 和 API101 不能放在同一个 APK 中。
本阶段只做 modern101 + classic，不生成 API100 stub，也不发两个 flavor。

## 核心接口

`HookBridge` 只暴露：

- 框架日志
- class 查找
- 按方法名安装 after-hook
- 读取字段、调用只读方法和静态方法

接口没有修改参数、修改返回值、跳过原方法或替换方法的入口。`HookCallback.args` 是防御性
拷贝，观察者即使误改数组也影响不到目标方法。所有回调异常由适配器和运行时双重 guard，
不会进入 `system_server`。

## 系统钩子迁移

- `SystemHook.install(bridge, classLoader)` 不再接收 `XC_LoadPackage.LoadPackageParam`。
- 候选表的类查找、方法安装、字段读取和日志全部通过 `HookBridge`。
- 现有候选、身份 allowlist、有界队列、心跳、受保护广播和 alpha12 风暴防护保持不变。
- `ModuleStatus` 的 classic 自 hook 仍然保留；modern API101 的激活状态在后续 PR 通过
  `XposedService` 绑定判断，不再依赖模块应用被自己 hook。

## 边界

- 本 PR 只做入口重构，不改变用户可见行为，不增加方法钩子。
- 真机仍需确认 classic 入口在目标 LSPosed 版本上照常加载；本 PR 不做模拟器测试。
