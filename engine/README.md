# engine：自动化指令编写模型

纯 Kotlin/JVM + kotlinx.serialization；不监听事件、不运行 Shell/JavaScript，不代表任何系统权限已授权。

## 入口与兼容

`com.flowglass.engine` 完整保持 `docs/authoring-contract.md` 的模型、注册表、校验器及 RuleCodec 签名。新增公开类型仅有：

- `EngineLimits`：公开上限常量。
- `RuleValidationException(val issues: List<ValidationIssue>) : IllegalArgumentException`：带中文路径的整批失败信息。

元数据共 61 项（27 触发器、8 条件、26 动作），涵盖方案第 5/6/10 节变量、匹配、流程控制与脚本编写。权限/最低层仅声明能力需求；Shell 的 root/backend 仍需要未来执行端按参数检查授权。

## 导入/保存流程

1. `decodeBundle(text)`：单条原文档 JSON 或版本化 bundle；检查格式、内容与安全上限，保留原 ID/enabled，未知字段/类型/版本报错。
2. `scriptWarnings(bundle)`：完整列出每个 Shell、JS、表达式，包括子动作、else 分支及 JSON 参数深层脚本键；不截断、不去重、不执行。UI 必须完整展示并二次确认。
3. `prepareImport(bundle)`：再次校验，给所有规则分配新 ID、停用；重映射同批次 `action.call_manual.params.ruleId`，保持组件 ID 与匹配树引用。
4. 同批次调用目标必须为 manual；批次外 ruleId 原样保留（engine 没有库上下文，存在性由持久化/执行集成层核验）。全局名重复拒绝；与现有数据库的冲突由事务导入负责。
5. `encodeRule/decodeRule` 是草稿存储协议：允许缺名称、参数非法等可修正内容，但仍限制大小、结构；不重新生成已有 ID 或切换 enabled。保存前另调用 `RuleValidator.validate`。
6. `exportBundle` 必须通过校验，并且结果能通过导入的 1 MiB 文件限制；超限明确失败，不截断。

变量 int 是严格 Long JSON 数字，禁止字符串/小数/科学计数法/溢出；decimal 必须能表示为有限 Double。列表、字典中合法 JSON 数字原始词法通过定制序列化保留（例如 `9007199254740993`、`1.000000000000000001` 和 `1e999`），不经 Double 归一化；NaN/Infinity 不是合法 JSON。变量的动态引用只检查声明/作用域和静态类型，不求值脚本。

## 包含边界的上限

- UTF-8 文件：1 MiB；bundle：100 条规则。
- 每规则：总组件 1000、局部变量 256；bundle 全局变量 256。
- 动作/匹配树：32 层；每匹配树 1000 节点。
- JSON 值：64 层；输入 JSON：128 层、100000 节点。
- 匹配树 ref 指组件 ID，排序不影响引用；删除导致悬空引用时报错。

## 测试

```sh
./gradlew :engine:test --no-configuration-cache
# 不依赖 Android SDK 的独立工程（Gradle >= 8.13）
gradle -p engine/test-harness :engine:test
```

独立工程产物位于 `engine/test-harness/build/engine/`；与根工程 `engine/build/` 隔离，不能共用 Kotlin 增量编译输出。并行 Android 构建持有根 Configuration Cache 锁时，使用独立工程或根命令加 `--no-configuration-cache`。

Kotlin JVM/serialization 插件 2.4.10，serialization-json 1.9.0，JUnit 4.13.2；使用当前 JDK 输出 Java/Kotlin 17 字节码。原始方案合法 JSON 夹具位于 `src/test/resources/document-rule.json`。
