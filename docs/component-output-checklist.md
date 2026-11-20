# 组件输出检查清单

新增或修改触发器、动作、流程块时逐项确认。规范见 [`output-variables.md`](output-variables.md)。

## 元数据

- [ ] `ComponentMeta.outputs` 声明专属 `OutputSpec`，不使用共享通用 key。
- [ ] 没有自然数据的组件不声明 outputs，由 `OutputCatalog` 生成 `<typeId>.primary` 主输出。
- [ ] 每个 `OutputSpec.key` 稳定、唯一，命名形如 `<域>.<字段>`。
- [ ] `label` 是中文界面文案；`defaultName` 通过 `VariableName.isValid`。
- [ ] `description` 说明值的来源和单位；没有敏感内容。
- [ ] 条件组件不声明输出。

## 运行期

- [ ] 有专属输出的触发器在 `TriggerDispatcher` 写入专属值。
- [ ] 没有专属输出的触发器写入主输出（本次触发时间）。
- [ ] 动作在 `ActionOutcome.outputs` 返回专属 key，不依赖共享状态/结果/名称。
- [ ] 没有专属输出的动作/流程块由执行器写入主输出结果摘要。
- [ ] 失败路径也写组件自己的主输出。
- [ ] 输出值按 `VariableType` 转换；类型不符时跳过，不判动作失败。
- [ ] 输出名进入 `RunScopes.outputNames`，`set_var` 和脚本 `set` 被拒绝。

## 映射与持久化

- [ ] `CommandItem.outputs` 与 `RuleComponent.outputs` 双向映射。
- [ ] `RuleDocumentMapper` 的提前返回比较包含 `outputs`。
- [ ] `ComponentDraft.dirty` 比较 `outputs`。
- [ ] 新增/替换草稿、`withIds` 保留 `outputs`。
- [ ] 旧文件加载后由 `normalizeRule` 补默认绑定。
- [ ] `RuleJson.encode` 前规范化，保存后规则自包含。

## UI

- [ ] 输出页能列出全部 `allOutputs`，显示类型和说明。
- [ ] 改名、恢复默认可用；没有「添加别名」入口，历史别名可「移除别名」。
- [ ] 没有启用/禁用开关：有这个组件就有这个输出，保存后恒为启用。
- [ ] 至少一个启用；非法名/重名就地提示。
- [ ] 条件字段没有输出入口。
- [ ] 无文本参数的组件可从顶栏进入。
- [ ] 流程块页顶栏有入口。
- [ ] 方法调用提取项只读，并链接现有提取编辑器。

## 测试

- [ ] schema：数量、非空、key 唯一、默认名合法。
- [ ] 运行期：触发器条件前可见；动作后可见；失败写通用值；禁用不写；别名可用。
- [ ] 只读：`set_var` 与脚本写入失败。
- [ ] 序列化：v5 迁移、v6 往返、未知 key/重名/全禁用被拒绝。
- [ ] UI：输出页、双图标、条件无图标、块入口。
- [ ] `./gradlew :engine:testAndroidHostTest :androidApp:testDebugUnitTest --no-configuration-cache` 全绿。
