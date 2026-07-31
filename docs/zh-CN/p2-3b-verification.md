# P2.3b 不可变 Manifest 1.1 Release 固定验证候选

状态：维护者已于 2026-07-31 验收。P2.3 继续保持 `in-progress`；P2.3b 为
`completed`。

## 范围

P2.3b 闭合以下有界 Release 工作流：

```text
读取一个作用域 Application Draft
  -> 快照匹配的 Application 与 Binding 乐观版本
  -> 解析准确 Model Route 与 Prompt 引用
  -> 解析每个有序不透明 Knowledge 组合
  -> 要求 READY Index 与可执行 Retrieval Policy
  -> 构建并完整验证服务端权威 Manifest
  -> 计算规范摘要
  -> 插入一个不可变 ReleaseBundle，或整体回滚
```

本阶段不执行生产检索、不写 Run 证据、不编排 Grounded 生成，也不验证引用。

## 权威与边界

- 阶段：P2 / P2.3 / P2.3b。
- 所有者：Release。
- 只使用获准的 Application、Capability Registry 与 Knowledge 公共 API。
- Release 不读取其他模块的表，也不导入内部包。
- 标准 Release 请求已移除客户端 Manifest，只接受语义化 Release 版本。
- 无需数据库迁移；现有带作用域、只插入的 `release_bundle.manifest` JSONB 与制品摘要
  足以承载本切片。
- ADR-0006 已授权 Manifest 1.1 固定与 Release 到 Knowledge 依赖，不需要新 ADR。

## 完整离线 Schema 验证

Apvero 固定 `com.networknt:json-schema-validator:3.0.2`，兼容 Java 21 与 Jackson 3，
支持 JSON Schema Draft 2020-12，采用 Apache-2.0 许可证。

只有 Manifest 1.0 与 1.1 Schema ID 从打包的 Classpath 资源注册。Validator 只从该
白名单选择，不解析调用者提供或远程 Schema URI。Format Assertion、封闭对象、
CHAT/RAG 条件规则、边界、数量、Pattern 与 `latest` 拒绝都会执行。未知版本在 Schema
查找前失败。

验证失败只暴露稳定错误码：

- `APVERO_RELEASE_MANIFEST_INVALID`；
- `APVERO_RELEASE_MANIFEST_UNSUPPORTED`；
- `APVERO_RELEASE_KNOWLEDGE_BINDING_INVALID`。

## 权威构建与兼容

- CHAT Release 继续生成 Manifest 1.0，保留历史 P1 执行。
- RAG Release 生成 Manifest 1.1，包含 1 至 16 个有序 Knowledge Pin。
- Model Route、Prompt、运行时 Temperature 与最大输出 Token 来自作用域 Capability
  Registry 投影。
- Index 与 Retrieval Policy 规范引用来自作用域 Knowledge 投影。
- Retrieval Policy 是否可执行，由 Knowledge 根据固定的算法、估算器、Retention
  来源和空证据行为决定。
- Application 版本必须与 Binding Set 版本一致，混合 Draft 快照不能发布。
- Policy 引用按首次 Binding 顺序去重；Knowledge Binding 保持准确 Application 顺序。
- Manifest 1.0 与 1.1 在读取时都会验证；未知已存 Schema 版本失败关闭。
- 现有 Provider 支持 Manifest 1.0 CHAT 与 Manifest 1.1 CHAT。P2.3d 提供 Grounded
  编排前，Manifest 1.1 RAG 会被拒绝，绝不静默执行为 CHAT。

## 持久化、失败与遥测

Release 事务只在全部解析和完整验证成功后插入。任一 Pin 失败都不会产生部分
ReleaseBundle。现有数据库触发器继续拒绝全部 Release 更新或删除。

`apvero.release.pin.validation` 与 `apvero.release.pin.validation.latency` 只暴露有界
Runtime Mode、Outcome 和 Failure Family 标签。Tenant、Workspace、Application、
Release、Model、Prompt、Index、Policy、Digest 与正文身份永不成为指标标签。

## 验证

证据覆盖：

- 完整有效 Manifest 1.0 与 RAG 1.1 接受；
- 缺字段、额外属性、`latest`、条件形状和未知 Schema 拒绝；
- 仅服务端构建的标准 Release 请求契约；
- 有序多 Binding 解析与规范引用固定；
- 不支持 Policy、空选择、过期 Draft 快照和错误顺序拒绝；
- 准确制品摘要持久化与数据库不可变执行；
- 后续 Binding 失败时回滚此前已经解析的 Binding；
- 历史 CHAT 构建及其路径不访问 Knowledge；
- 读取时验证已存 Manifest；
- Deterministic 与 Spring AI Provider 阻止 RAG 降级为 CHAT；
- 有界遥测标签；
- Spring Modulith 与 ArchUnit 依赖验证。

本地已执行：

- 完整 Gradle 测试套件与可启动 Platform Server JAR；
- P2.3b 单元、契约、Provider 兼容和真实 PostgreSQL/Testcontainers 集成套件；
- TypeScript 严格类型检查与 Console 单元测试；
- 英文与简体中文 Key/Placeholder 校验；
- OpenAPI 3.1 Lint 与完整 Manifest 1.1 示例验证；
- 默认和 Knowledge Profile Compose 配置验证；
- 打包 Schema 存在性与 Source Diff 检查。

生产检索性能基准按既有 P2.2 验证策略继续明确为选择性执行并被跳过；P2.3b 不改变检索
热路径。

## 依赖与回滚

Gradle Dependency Insight 精确解析 `json-schema-validator:3.0.2`。上游项目标明
Apache-2.0 许可证。P2.3 验收前，里程碑 PR 仍必须通过干净主机依赖解析与仓库安全审查。

在任何 Manifest 1.1 RAG 行出现前，可回滚到上一 P1 兼容二进制。出现后，回滚下限是
保留新增不可变行的 P2 兼容二进制。关闭 Knowledge 会阻止新的 RAG Release 解析，但
绝不重写或降级已经保存的 Manifest。

## 已知限制

1. Manifest 1.1 RAG 已不可变且可检查，但在 P2.3d 前有意不可执行。
2. Runtime 检索证据属于 P2.3c。
3. Grounded 编排与 `NO_EVIDENCE` 行为属于 P2.3d。
4. 结构化 Answer 与 Citation 验证属于 P2.3e。
5. Schema 在完整 P2.3 闭环前继续保持 Runtime `contract-only` 状态。

## 退出声明

维护者已于 2026-07-31 验收 P2.3b，并确认：

> Apvero 能仅使用权威、准确、Workspace 作用域投影，把一个一致的 RAG Application
> Draft 转换为经过完整验证、内容寻址、不可变的 Manifest 1.1 ReleaseBundle，同时
> 保留 Manifest 1.0 CHAT，并阻止无依据的降级执行。
