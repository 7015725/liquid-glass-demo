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

## API101 入口（alpha18）

- `androidApp/src/main/resources/META-INF/xposed/module.prop` 声明 `minApiVersion=101`、
  `targetApiVersion=101`、`staticScope=false`、`autoHotReload=false`。
- `java_init.list` 只列 `com.flowglass.demo.xposed.Api101Entry`；`scope.list` 初始包含
  `com.flowglass.demo` 和 `system`。modern 的系统框架作用域是 `system`，不再用 `android` 判断。
- `Api101Entry` 在 `onSystemServerStarting` 安装现有的系统钩子；`onModuleLoaded` 只记录进程；
  `onPackageReady` 暂时只记录，方法钩子在后续 PR 安装。
- `Api101HookBridge` 用 `hook(method).setExceptionMode(PROTECTIVE).intercept` 实现统一的
  after 语义：`chain.proceed()` 恰好一次，参数复制后观察，返回值原样透传。
- modern 框架不再把模块注入自己的 App，因此 `ModernModuleStatus` 通过
  `XposedServiceHelper` 读取 `XposedService` 的 API、框架版本和作用域来标记激活；
  `ModuleStatus` 同时保留 classic 自 hook 路径。
- API101 的 `onPackageLoaded` / `onPackageReady` 带 Android Q 门槛；Android 8/9 的方法钩子
  后续需要走 classic 或明确禁用，不能把 modern 方法钩子当成全版本可用。

## 边界

- 本 PR 只接入 modern101 + classic；不生成 API100 stub、不发第二个 flavor。
- 真机需要确认：LSPosed 版本/API101、模块列表中显示 modern 入口、作用域出现
  `system`、系统钩子仍能加载且无广播风暴；这些不能由 JVM 测试代替。

